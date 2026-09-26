package com.trialportal.trial;

import com.trialportal.TrialPortal;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.arena.RoomLibrary;
import com.trialportal.block.TrialPortalBlock;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.config.TrialConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务端启动后的自驱断言（只在 {@code -Dtrial_portal.selfTest=true} 时跑，正常游戏不会触发）。
 * 存在的理由：这台机器上没人手点客户端，运行期行为必须能自己变红。
 * 任何一条断言失败都会让启动崩溃，从而让 gradlew runGameTestServer 以非 0 退出。
 */
@Mod.EventBusSubscriber(modid = TrialPortal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrialSelfTest
{
    public static final String FLAG = "trial_portal.selfTest";

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("trial_portal.selftest");

    /** 跑过多少条断言：写进标记文件，防止「一条没跑」被当成全绿。 */
    private static int assertions;

    private TrialSelfTest() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        if (!Boolean.getBoolean(FLAG))
        {
            return;
        }
        List<String> failures = run(event.getServer());
        int passed = assertions - failures.size();
        // 落一个标记文件：runGameTestServer 即使服务端在注册表阶段崩掉也会退 0，
        // 光看退出码会把「一条断言都没跑」当成全绿，所以门必须验这个文件（见 build.gradle 的 selfTest 任务）。
        try
        {
            java.nio.file.Path marker = event.getServer().getServerDirectory().toPath().resolve("selftest-result.txt");
            java.nio.file.Files.writeString(marker, "PASS=" + passed + " FAIL=" + failures.size()
                    + " at=" + java.time.Instant.now() + System.lineSeparator());
        }
        catch (java.io.IOException exception)
        {
            LOGGER.warn("[selftest] 标记文件写不出去：{}", exception.getMessage());
        }
        if (failures.isEmpty())
        {
            LOGGER.info("[selftest] 全部 {} 条断言通过", passed);
            return;
        }
        failures.forEach(failure -> LOGGER.error("[selftest] 失败：{}", failure));
        throw new IllegalStateException("[selftest] " + failures.size() + " 条断言失败：" + String.join(" | ", failures));
    }

    /** @return 失败原因列表，空表示全通过 */
    public static List<String> run(MinecraftServer server)
    {
        List<String> failures = new ArrayList<>();

        check(failures, TrialLibrary.ids().size() >= 3,
                "试炼定义至少 3 份，实际 " + TrialLibrary.ids().size());
        check(failures, BossAffixLibrary.ids().size() >= 8,
                "词条至少 8 条，实际 " + BossAffixLibrary.ids().size());
        check(failures, TrialLibrary.resolve(TrialMode.CHALLENGE, null) != null, "未绑定时应能按模式取到默认试炼");
        check(failures, TrialLibrary.resolve(TrialMode.TOWER, null) != null, "未绑定时应能按模式取到爬塔试炼");

        TrialDefinition tower = TrialLibrary.get("trial_portal:tower");
        if (tower != null)
        {
            TrialDefinition.WaveContent first = tower.waveFor(0);
            TrialDefinition.WaveContent bossFloor = tower.waveFor(29); // 第 30 层：boss_every_floors=10 且该层段 boss=true
            check(failures, first != null && !first.mobs.isEmpty(), "第 1 层能取到内容");
            check(failures, bossFloor != null && bossFloor.bossIndex >= 0, "第 30 层应算出 BOSS 波");
            check(failures, bossFloor != null && bossFloor.affixPool != null && !bossFloor.affixPool.isEmpty(),
                    "层段专用 boss_affix_pool 应传到运行期（设计文档第 1 个坑）");
            if (bossFloor != null && !bossFloor.mobs.isEmpty() && tower.scaling != null)
            {
                TrialDefinition.Tier tier = tower.scaling.tiers.stream()
                        .filter(candidate -> bossFloor.floor >= candidate.from && bossFloor.floor <= candidate.to)
                        .findFirst().orElse(null);
                String expected = tier == null ? null : tier.mobs.get(0);
                check(failures, expected != null && expected.equals(bossFloor.mobs.get(bossFloor.bossIndex).entity),
                        "第 " + bossFloor.floor + " 层是 BOSS 层，应取该层段 mobs[0]=" + expected
                                + "，实际 " + bossFloor.mobs.get(bossFloor.bossIndex).entity);
            }
            TrialDefinition.WaveContent deep = tower.waveFor(500);
            check(failures, deep != null && deep.mobs.stream().allMatch(entry -> entry.count <= tower.scaling.maxCountPerEntry),
                    "501 层的怪量应被 max_count_per_entry 钳制");
        }

        // 注册面：三个方块 / 三个物品 / 方块实体类型 / 创造栏都要真的进注册表
        for (String name : List.of("challenge_portal", "tower_portal", "trial_portal"))
        {
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, name);
            check(failures, net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(key) != null,
                    "方块 " + key + " 已注册");
            check(failures, net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(key) != null,
                    "物品 " + key + " 已注册");
        }
        // 场地维度与平台生成
        ResourceKey<Level> arenaKey = com.trialportal.arena.ArenaService.ARENA;
        check(failures, server.getLevel(arenaKey) != null, "试炼场地维度 " + arenaKey.location() + " 已加载");
        check(failures, net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, "arena_exit")) != null,
                "返回终端方块已注册");
        int radius = com.trialportal.arena.ArenaService.radius();
        int spacing = com.trialportal.arena.ArenaService.spacing();
        check(failures, spacing >= radius * 2 + 8,
                "隔间间隔 " + spacing + " 应不小于两倍平台半径再加边距（" + (radius * 2 + 8) + "），否则隔壁间会挨上");
        var arenaLevel = server.getLevel(arenaKey);
        if (arenaLevel != null)
        {
            BlockPos lobby = com.trialportal.arena.ArenaService.cellOrigin(com.trialportal.arena.ArenaService.LOBBY_CELL);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, lobby, null);
            int first = countPlatformBlocks(arenaLevel, lobby, radius);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, lobby, null);
            int second = countPlatformBlocks(arenaLevel, lobby, radius);
            check(failures, first > 0 && first == second,
                    "平台生成必须幂等：第一次 " + first + " 块，第二次 " + second + " 块");
            check(failures, arenaLevel.getBlockState(lobby.offset(-4, 1, 0)).getBlock()
                            instanceof com.trialportal.block.TrialPortalBlock,
                    "大厅应摆出闯关终端");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, 4)).getBlock()
                            instanceof com.trialportal.block.ArenaExitBlock,
                    "大厅应摆出返回终端");
            BlockPos cell = com.trialportal.arena.ArenaService.cellOrigin(1);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, cell, TrialMode.CHALLENGE);
            check(failures, arenaLevel.getBlockState(cell.offset(0, 1, -3)).getBlock() instanceof TrialPortalBlock,
                    "隔间必须有该模式的终端，否则玩家进去就没法继续下一波与结算");
            check(failures, arenaLevel.getBlockState(cell.offset(0, 1, 3)).getBlock()
                            instanceof com.trialportal.block.ArenaExitBlock,
                    "隔间必须有返回终端");
            int cellFirst = countPlatformBlocks(arenaLevel, cell, radius);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, cell, TrialMode.CHALLENGE);
            int cellSecond = countPlatformBlocks(arenaLevel, cell, radius);
            check(failures, cellFirst > 0 && cellFirst == cellSecond,
                    "隔间重复建台必须幂等：第一次 " + cellFirst + " 块，第二次 " + cellSecond + " 块");
            // 试炼模式的隔间要往外建房，围栏南侧必须留门，否则玩家清完一波回终端时被自家围栏挡在台面上
            BlockPos trialCell = com.trialportal.arena.ArenaService.cellOrigin(2);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, trialCell, TrialMode.TRIAL);
            check(failures, arenaLevel.getBlockState(trialCell.offset(0, 1, radius)).isAir(),
                    "试炼隔间的围栏要在 +Z 那侧留出 3 格门（走廊同宽），实际是堵死的");
            check(failures, !arenaLevel.getBlockState(trialCell.offset(0, 1, -radius)).isAir()
                            && !arenaLevel.getBlockState(trialCell.offset(-radius, 1, 0)).isAir(),
                    "门只开朝房间那一侧，其余三面仍要围住——门外就是虚空");
            com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 2);
            int orphanCellsBefore = com.trialportal.arena.ArenaService.usedCellCount(server);
            com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 1);
            int afterRelease = countPlatformBlocks(arenaLevel, cell, radius);
            check(failures, afterRelease == 0,
                    "结算后隔间平台必须被拆掉，残留 " + afterRelease + " 块");
            check(failures, countPlatformBlocks(arenaLevel, lobby, radius) > 0,
                    "拆隔间不能顺手把大厅也拆了");
            check(failures, com.trialportal.arena.ArenaService.usedCellCount(server) == orphanCellsBefore,
                    "releaseCellAt 只拆台，不该动记账");
            check(failures, com.trialportal.arena.ArenaService.findSafeLanding(arenaLevel, lobby.offset(0, 3, 0))
                            .getY() <= lobby.getY() + 1,
                    "落点安全搜索应把悬空坐标拉回平台上，而不是原地不动");
        }

        // 越界判定：平台圈 ∪ 已建区域，两块都得算在场（真机踩过「刚开局就判成逃跑」）
        BlockPos platform = new BlockPos(0, 64, 0);
        net.minecraft.world.phys.AABB roomBox = new net.minecraft.world.phys.AABB(
                -1, 64, 11, 10, 71, 22); // 第一间房沿 +Z 建在 11~22，站在台上（z=0）离它 11 格
        check(failures, TrialManager.withinAllowedArea(new net.minecraft.world.phys.Vec3(0.5, 65, 0.5), platform, 8.0D, roomBox),
                "玩家站在出发平台中央、房已建在远处时不该被判成逃跑");
        check(failures, TrialManager.withinAllowedArea(new net.minecraft.world.phys.Vec3(4.5, 65, 15), platform, 8.0D, roomBox),
                "房内（超出半径但在已建区域里）应当算在场");
        check(failures, !TrialManager.withinAllowedArea(new net.minecraft.world.phys.Vec3(0.5, 65, 90), platform, 8.0D, roomBox),
                "又远又不在任何已建区域里，才该判逃跑");
        check(failures, TrialManager.withinAllowedArea(new net.minecraft.world.phys.Vec3(0.5, 65, 900), platform, 0.0D, null),
                "abandon_distance=0 表示永不判放弃");

        // 房间库与站怪点
        check(failures, RoomLibrary.ids().size() >= 5, "自带房间至少 5 间，实际 " + RoomLibrary.ids().size());
        check(failures, !RoomLibrary.bossRooms().isEmpty(), "至少有一间 boss_room=true 的终点房");
        check(failures, !RoomLibrary.normalRooms().isEmpty(), "至少有一间普通房");
        List<String> plan = RoomLibrary.plan(2, server.overworld().getRandom());
        check(failures, plan != null && plan.size() == 3, "规划 2 间普通房应得到 3 间（含终点房），实际 "
                + (plan == null ? "null" : plan.size()));
        if (plan != null)
        {
            RoomDefinition last = RoomLibrary.get(plan.get(plan.size() - 1));
            check(failures, last != null && last.bossRoom, "序列最后一间必须是 BOSS 房");
            for (String roomId : plan)
            {
                RoomDefinition room = RoomLibrary.get(roomId);
                check(failures, room != null && room.spawnPointsAt(BlockPos.ZERO).size() == room.spawnPoints,
                        roomId + " 声明的站怪点数与实算数要一致");
            }
            // 同一随机种子规划两次，结果应当一样（可复现性：玩家背板的前提）
            List<String> once = RoomLibrary.plan(2, net.minecraft.util.RandomSource.create(1234L));
            List<String> again = RoomLibrary.plan(2, net.minecraft.util.RandomSource.create(1234L));
            check(failures, once != null && once.equals(again), "同种子规划必须可复现");

            if (arenaLevel != null)
            {
                // 拿库里最宽的一间来验清场：只有它会越过平台半径，窄房验不出这个 bug
                RoomDefinition room = RoomLibrary.widestRoom();
                if (room == null)
                {
                    failures.add("取不到最宽的一间房，房间几何断言没跑");
                }
                else
                {
                    check(failures, room.width() == RoomLibrary.maxWidth(),
                            "清场盒子的宽度口径应与库里最宽的房一致");
                    BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(4);
                    BlockPos roomOrigin = cellOrigin.offset(-room.width() / 2, 0,
                            com.trialportal.arena.ArenaService.radius() + 4);
                    com.trialportal.arena.RoomBuilder.Built built = com.trialportal.arena.RoomBuilder.build(
                            arenaLevel, room, roomOrigin, net.minecraft.util.RandomSource.create(4321L));
                    // 地板要和隔间平台同一层，否则走廊与房间差一格，玩家走进去会掉一下
                    check(failures, isGround(arenaLevel, roomOrigin),
                            "地板必须落在 origin.y 这一层（与大厅平台同约定），"
                                    + room.id() + " @ " + roomOrigin.toShortString() + " 实际不是");
                    int bad = 0;
                    for (BlockPos point : built.spawnPoints())
                    {
                        if (!isGround(arenaLevel, point.below()) || !arenaLevel.getBlockState(point).isAir()
                                || !arenaLevel.getBlockState(point.above()).isAir())
                        {
                            bad++;
                        }
                    }
                    check(failures, bad == 0,
                            room.id() + " 有 " + bad + " 个站怪点脚下不实心或头顶被挡，怪会卡进墙/悬空");
                    check(failures,
                            arenaLevel.getBlockState(roomOrigin.offset(room.width() / 2, 1, -1)).isAir(),
                            "朝向走廊那一侧的门洞要真的空出来，否则玩家走不进这间房");
                    int reach = roomOrigin.getZ() + room.depth() - cellOrigin.getZ();
                    com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 4, reach);
                    int left = countRoomBlocks(arenaLevel, roomOrigin, room);
                    check(failures, left == 0, "结算清场没覆盖整间房，残留 " + left + " 块（最宽房 "
                            + RoomLibrary.maxWidth() + "，平台半径 "
                            + com.trialportal.arena.ArenaService.radius() + "）");
                    check(failures, !hasForcedChunkIn(arenaLevel,
                                    roomOrigin.offset(-1, 0, -1),
                                    roomOrigin.offset(room.width(), 0, room.depth())),
                            "拆完隔间还得把建房时强制加载的区块还回去，否则这些区块永远挂在内存里");
                    // 清场尺寸必须向房间库要：今天的房窄不代表以后也窄
                    probeTeardown(arenaLevel, failures);
                    // 真离场走的是 releaseCell(uuid)，进深要从记账里来——真机就是因为这里漏传留下一整间房
                    probeReleaseByAccounting(arenaLevel, server, failures, radius);
                }
            }
        }

        // 直接执行一遍 /tei trial list：比查指令树里有没有这个名字更硬
        try
        {
            int listed = server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tei trial list");
            check(failures, listed == TrialLibrary.ids().size(),
                    "/tei trial list 应回报 " + TrialLibrary.ids().size() + " 份试炼，实际 " + listed);
        }
        catch (Exception exception)
        {
            failures.add("/tei trial list 执行失败：" + exception);
        }

        ServerLevel level = server.overworld();
        BlockPos origin = level.getSharedSpawnPos();
        Vindicator boss = new Vindicator(EntityType.VINDICATOR, level);
        boss.setPos(origin.getX() + 0.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D);
        level.addFreshEntity(boss);

        BossAffix antiArmor = BossAffixLibrary.get("trial_portal:anti_armor");
        BossAffix bulwark = BossAffixLibrary.get("trial_portal:bulwark");
        BossAffix hunter = BossAffixLibrary.get("trial_portal:hunter");
        BossAffix crusher = BossAffixLibrary.get("trial_portal:crusher");
        if (antiArmor == null || bulwark == null || hunter == null || crusher == null)
        {
            failures.add("示例词条没加载齐，后面的断言跑不了");
            return failures;
        }
        BossAffix thorns = BossAffixLibrary.get("trial_portal:thorns");
        List<BossAffix> applied = List.of(antiArmor, bulwark, hunter, crusher, thorns);
        double baseHealth = attributeBase(boss, Attributes.MAX_HEALTH);
        BossAffixLibrary.applyTo(boss, applied);
        BossAffixLibrary.decorate(boss, applied);

        double multiplier = 1.0D;
        for (BossAffix affix : applied)
        {
            BossAffix.AttributeMod mod = affix.attributes.get("minecraft:generic.max_health");
            if (mod != null && "multiply_base".equalsIgnoreCase(mod.op))
            {
                multiplier += mod.value; // 每条各一个 multiply_base 修饰符，合起来只乘算一次基础值
            }
        }
        double expectedHealth = baseHealth * multiplier;
        check(failures, Math.abs(boss.getMaxHealth() - expectedHealth) < 0.51D,
                "血量倍率应只经一次乘算生效：期望 " + expectedHealth + "，实际 " + boss.getMaxHealth());
        check(failures, Math.abs(boss.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) - 1.0D) < 0.01D,
                "crusher 应把抗击退 set 成 1.0，实际 " + boss.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        check(failures, boss.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "thorns 的钻石胸甲已穿上");
        check(failures, boss.getItemBySlot(EquipmentSlot.CHEST).getEnchantmentLevel(
                        net.minecraftforge.registries.ForgeRegistries.ENCHANTMENTS.getValue(
                                BossAffixLibrary.parse("minecraft:thorns"))) == 3, "荆棘 III 已附上");

        CompoundTag persistent = boss.getPersistentData();
        check(failures, persistent.getString(BossAffixLibrary.TAG_MELEE_TYPE).equals("minecraft:magic"),
                "近战伤害类型替换没写进持久化数据：" + persistent.getString(BossAffixLibrary.TAG_MELEE_TYPE));
        check(failures, persistent.getString(BossAffixLibrary.TAG_IMMUNITY).contains("projectile"),
                "免疫列表没写进持久化数据：" + persistent.getString(BossAffixLibrary.TAG_IMMUNITY));

        DamageSources sources = level.damageSources();
        float healthBefore = boss.getHealth();
        // 弹射物伤害源：直接按 minecraft:arrow 造，IS_PROJECTILE 标签由伤害类型自带
        DamageSource arrowLike = new DamageSource(level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.ARROW), boss, boss);
        check(failures, !boss.hurt(arrowLike, 6.0F), "hunter 让 BOSS 免疫弹射物（hurt 被取消）");
        check(failures, boss.getHealth() == healthBefore, "免疫应当连扣血都不发生");
        check(failures, boss.hurt(sources.mobAttack(boss), 1.0F), "普通近战伤害不该被误免疫");

        // 区块重载后的实际表现：属性与词条都必须能从实体 NBT 里回来
        CompoundTag saved = new CompoundTag();
        boss.saveWithoutId(saved);
        check(failures, saved.contains("Attributes"), "实体 NBT 里应该有 Attributes 段");
        ListTag attributeEntries = saved.getList("Attributes", Tag.TAG_COMPOUND);
        check(failures, containsTrialPortalModifier(attributeEntries),
                "multiply_base 必须是 permanent modifier 才会进实体存档（设计文档第 4 个坑），实际 " + attributeEntries);
        check(failures, saved.contains("ForgeData"), "getPersistentData 应随实体存档");
        ListTag dropChances = saved.getList("ArmorDropChances", Tag.TAG_FLOAT);
        check(failures, dropChances.size() == 4 && dropChances.getFloat(2) == 0.0F,
                "BOSS 装备掉率应被设为 0（chest 槽），实际 " + dropChances);

        Vindicator restored = new Vindicator(EntityType.VINDICATOR, level);
        restored.load(saved);
        check(failures, Math.abs(restored.getMaxHealth() - boss.getMaxHealth()) < 0.51D,
                "从 NBT 恢复后血量应一致（" + restored.getMaxHealth() + " vs " + boss.getMaxHealth() + "）");
        check(failures, restored.getPersistentData().getString(BossAffixLibrary.TAG_IMMUNITY)
                        .contains("projectile"),
                "从 NBT 恢复后免疫列表应还在");

        int interval = TrialConfig.GENERAL_TICK_INTERVAL.get();
        check(failures, interval >= 1, "tick_interval 至少为 1");
        boss.discard();
        return failures;
    }

    /** Attributes = [{Name, Base, Modifiers:[{Name, Amount, Operation}]}, ...] */
    private static boolean containsTrialPortalModifier(ListTag attributeEntries)
    {
        for (int i = 0; i < attributeEntries.size(); i++)
        {
            ListTag modifiers = attributeEntries.getCompound(i).getList("Modifiers", Tag.TAG_COMPOUND);
            for (int j = 0; j < modifiers.size(); j++)
            {
                if (modifiers.getCompound(j).getString("Name").startsWith("trial_portal:"))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static void check(List<String> failures, boolean condition, String message)
    {
        assertions++;
        if (condition)
        {
            LOGGER.info("[selftest] PASS {}", message);
        }
        else
        {
            failures.add(message);
        }
    }

    private static int countPlatformBlocks(ServerLevel level, BlockPos origin, int radius)
    {
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dz = -radius; dz <= radius; dz++)
            {
                if (!level.getBlockState(origin.offset(dx, 0, dz)).isAir())
                {
                    count++;
                }
            }
        }
        return count;
    }

    /** 实心可站（1.20.1 没有 isSolidRenderable，用红石导体这一条判据）。 */
    private static boolean isGround(ServerLevel level, BlockPos pos)    {
        return level.getBlockState(pos).isRedstoneConductor(level, pos);
    }

    /**
     * 造一间「比今天所有内容都大」的探针房，验证清场盒子会跟着房间库一起长。
     * 只拿现有内容验会漏：今天的房刚好塞得进按平台半径算的盒子，那就等于什么都没验。
     */
    private static void probeTeardown(ServerLevel arenaLevel, List<String> failures)
    {
        Map<String, RoomDefinition> library = RoomLibrary.snapshot();
        RoomDefinition probe = new RoomDefinition();
        probe.size = List.of(31, 12, 21);
        probe.spawnPoints = 8;
        probe.mobCap = 24;
        probe.ceiling = true;
        probe.pillars = 2;
        String error = probe.resolve("trial_portal:__probe_wide");
        if (error != null)
        {
            failures.add("探针房没过自己的校验（" + error + "），清场尺寸断言等于没跑");
            return;
        }
        try
        {
            Map<String, RoomDefinition> withProbe = new java.util.LinkedHashMap<>(library);
            withProbe.put(probe.id(), probe);
            RoomLibrary.replace(withProbe);
            check(failures, RoomLibrary.maxWidth() == 31,
                    "清场宽度应按库里最宽的房算，实际 " + RoomLibrary.maxWidth());
            BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(5);
            BlockPos origin = cellOrigin.offset(-probe.width() / 2, 0,
                    com.trialportal.arena.ArenaService.radius() + 4);
            com.trialportal.arena.RoomBuilder.build(arenaLevel, probe, origin,
                    net.minecraft.util.RandomSource.create(777L));
            int reach = origin.getZ() + probe.depth() - cellOrigin.getZ();
            com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 5, reach);
            int left = countRoomBlocks(arenaLevel, origin, probe);
            check(failures, left == 0, "31 格宽的房拆完还剩 " + left + " 块——清场盒子没按房间库算");
            check(failures, !hasForcedChunkIn(arenaLevel, origin.offset(-1, 0, -1),
                            origin.offset(probe.width(), 0, probe.depth())),
                    "探针房 force 的区块没还回去");
        }
        finally
        {
            RoomLibrary.replace(library);
        }
    }

    /**
     * 走真离场的那条路：{@code releaseCell(arena, manager, uuid, cell)}，进深从记账里读。
     * 真机就是栽在这里——调用点各自传 reach，漏传一次就在虚空里留下一整间房外加 force 着的区块。
     */
    private static void probeReleaseByAccounting(ServerLevel arenaLevel, MinecraftServer server,
                                                 List<String> failures, int radius)
    {
        RoomDefinition room = RoomLibrary.widestRoom();
        if (room == null)
        {
            failures.add("没有可用房间，离场记账断言没跑");
            return;
        }
        java.util.UUID probePlayer = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:reach".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TrialManager manager = TrialManager.get(server);
        BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(6);
        java.util.Set<Long> forcedBefore = new java.util.HashSet<>(arenaLevel.getForcedChunks());
        com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, cellOrigin, TrialMode.TRIAL);
        BlockPos origin = cellOrigin.offset(-room.width() / 2, 0, radius + 4);
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, origin, net.minecraft.util.RandomSource.create(999L));
        manager.recordReach(probePlayer, origin.getZ() + room.depth() - cellOrigin.getZ());
        int recorded = manager.reachOf(probePlayer);
        check(failures, recorded > 0, "进深应记在账上，而不是只活在会话对象里");
        com.trialportal.arena.ArenaService.releaseCell(arenaLevel, manager, probePlayer, 6);
        int left = countRoomBlocks(arenaLevel, origin, room);
        check(failures, left == 0, "按记账进深离场仍没拆干净，最远那间房还剩 " + left + " 块（记进深 "
                + recorded + "，拆台开关 " + com.trialportal.config.TrialConfig.ARENA_CLEAR_CELL_ON_END.get()
                + "，房 @ " + origin.toShortString() + "）");
        check(failures, manager.reachOf(probePlayer) == 0, "freeCell 应把进深一起销账");
        // 建台 force 过的区块必须一个不剩地还回去：真机就是因为两边算法不一致，漏下一整列 chunk
        java.util.Set<Long> leaked = new java.util.HashSet<>(arenaLevel.getForcedChunks());
        leaked.removeAll(forcedBefore);
        check(failures, leaked.isEmpty(),
                "拆完之后场地里多出 " + leaked.size() + " 个仍被强制加载的区块（建台与拆台的 force 口径不一致）");
    }

    /** 这间房（含外墙与封顶）还剩多少非空气方块。 */
    private static int countRoomBlocks(ServerLevel level, BlockPos origin, RoomDefinition room)
    {
        int count = 0;
        for (int dx = -1; dx <= room.width(); dx++)
        {
            for (int dz = -1; dz <= room.depth(); dz++)
            {
                for (int dy = 0; dy <= room.height() + 1; dy++)
                {
                    if (!level.getBlockState(origin.offset(dx, dy, dz)).isAir())
                    {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean hasForcedChunkIn(ServerLevel level, BlockPos from, BlockPos to)
    {
        var forced = level.getForcedChunks();
        for (int cx = from.getX() >> 4; cx <= to.getX() >> 4; cx++)
        {
            for (int cz = from.getZ() >> 4; cz <= to.getZ() >> 4; cz++)
            {
                if (forced.contains(net.minecraft.world.level.ChunkPos.asLong(cx, cz)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** 属性基础值（1.20.1 的 LivingEntity 上没有这个便利方法，测试里自己取一次）。 */
    private static double attributeBase(LivingEntity entity, net.minecraft.world.entity.ai.attributes.Attribute attribute)
    {
        return entity.getAttribute(attribute).getBaseValue();
    }
}
