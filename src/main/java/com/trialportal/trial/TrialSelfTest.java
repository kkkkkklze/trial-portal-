package com.trialportal.trial;

import com.trialportal.TrialPortal;
import com.trialportal.arena.ArenaService;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.arena.RoomLibrary;
import com.trialportal.block.TrialPortalBlock;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.config.TrialConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 启动段能验的性质都在这儿。实体相关的判据（扫怪真把怪清掉没有）不在这里，也不在任何无头门里：
     * {@code runGameTestServer} 在 {@code ServerStartedEvent} 之后一条 tick 都不跑就 Stopping server，
     * 而 {@code addFreshEntity} 的怪要世界真的 tick 起来才进得了实体查询（实测手动 {@code level.tick}
     * 推 20 次也不注入）。所以那一条走真机（playtest-bridge），这里只留盒子与记账的硬判据。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        if (!Boolean.getBoolean(FLAG))
        {
            return;
        }
        finish(event.getServer(), run(event.getServer()));
    }

    /** 写标记文件（门验的就是它），失败则把自测进程打红。 */
    private static void finish(MinecraftServer server, List<String> failures)
    {
        int passed = assertions - failures.size();
        // 落一个标记文件：runGameTestServer 即使服务端在注册表阶段崩掉也会退 0，
        // 光看退出码会把「一条断言都没跑」当成全绿，所以门必须验这个文件（见 build.gradle 的 selfTest 任务）。
        try
        {
            java.nio.file.Path marker = server.getServerDirectory().toPath().resolve("selftest-result.txt");
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
            // 逐层指定（scaling.floors）：写死的那几层原样出，没写的层继续走层段轮换
            TrialDefinition.WaveContent floor2 = tower.waveFor(1);
            TrialDefinition.WaveContent floor3 = tower.waveFor(2);
            TrialDefinition.WaveContent floor4 = tower.waveFor(3);
            TrialDefinition.WaveContent floor10 = tower.waveFor(9);
            check(failures, first != null && first.mobs.size() == 1
                            && "minecraft:zombie".equals(first.mobs.get(0).entity)
                            && first.mobs.get(0).count == 2,
                    "第 1 层应固定 2 只僵尸（实际 " + describe(first) + "）");
            check(failures, floor2 != null && floor2.mobs.size() == 2 && "第 2 层 · 两翼".equals(floor2.title),
                    "第 2 层没按 floors 写的编排出来（实际 " + describe(floor2) + "）");
            check(failures, floor3 != null && floor3.mobs.size() == 1
                            && "minecraft:creeper".equals(floor3.mobs.get(0).entity)
                            && floor3.mobs.get(0).count == 3
                            && floor3.strength != null
                            && Math.abs(floor3.strength.speedMultiplier - 1.15D) < 0.0001D,
                    "第 3 层的苦力怕编排或逐层强度没生效（实际 " + describe(floor3) + "）");
            check(failures, floor4 != null && !floor4.mobs.isEmpty() && "第 4 层".equals(floor4.title),
                    "没在 floors 里出现的层仍应走层段轮换（实际 " + describe(floor4) + "）");
            check(failures, floor10 != null && floor10.bossIndex == 0 && !floor10.mobs.isEmpty()
                            && floor10.mobs.get(0).boss
                            && "minecraft:vindicator".equals(floor10.mobs.get(0).entity)
                            && floor10.mobs.size() == 2 && floor10.mobs.get(1).count == 3,
                    "第 10 层的 BOSS 编排（vindicator 当 BOSS + 3 只掠夺者）没落地（实际 " + describe(floor10) + "）");
            check(failures, floor10 != null && floor10.rewards.stream()
                            .anyMatch(reward -> "minecraft:golden_apple".equals(reward.item)),
                    "逐层指定的额外奖励没进来");
            check(failures, first != null && !first.mobs.isEmpty()
                            && first.mobs.get(0) != tower.scaling.floors.get(0).mobs.get(0),
                    "逐层编排的条目必须是复制件——把模型对象交给状态机改（打 boss 标记）会污染下一次取层");
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

        // 逐层编排的两条校验：同一层配两次、以及超出层段覆盖范围（后者会静默取不到内容＝塔在那层断掉）
        check(failures, probeDuplicateFloorRejected(), "scaling.floors 里同一层配了两次必须被校验拦下");
        check(failures, probeFloorBeyondTiersRejected(), "floors 指向 tiers 没覆盖的层必须被校验拦下");

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
                "往返门方块已注册");
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
            int gap = Math.max(1, (radius - 1) / 3); // 隔间槽距（隔间布局没动；大厅重排走自己的槽位）
            int row = -(radius - 1);
            int south = radius - 1;
            int arch = Math.max(1, Math.min(6, radius - 3));
            int exitSlot = Math.max(1, Math.min(5, radius - 4));
            check(failures, arenaLevel.getBlockState(lobby.offset(-arch, 1, row)).getBlock()
                            instanceof com.trialportal.block.TrialPortalBlock,
                    "大厅北沿左位应摆出闯关门（终端不许占场地中央）");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, row)).getBlock()
                            instanceof com.trialportal.block.TrialPortalBlock,
                    "大厅北沿正中应是爬塔门（三拱的中位）");
            check(failures, arenaLevel.getBlockState(lobby.offset(arch, 1, row)).getBlock()
                            instanceof com.trialportal.block.TrialPortalBlock,
                    "大厅北沿右位应摆出试炼门");
            check(failures, arenaLevel.getBlockState(lobby.offset(-exitSlot, 1, south)).getBlock()
                            instanceof com.trialportal.block.ArenaExitBlock
                            && arenaLevel.getBlockState(lobby.offset(exitSlot, 1, south)).getBlock()
                                    instanceof com.trialportal.block.ArenaExitBlock,
                    "大厅南沿两侧应各有一座往返门（与守塔之枢同排）");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, 0)).isAir()
                            && arenaLevel.getBlockState(lobby.offset(0, 1, 4)).isAir(),
                    "场地中央不许有功能方块：挡打斗区，也被玩家当成'重复出现的传送门'");
            // 大厅永远不拆台，旧布局/异常留下的终端必须被下一次建台抹平（用户报的"重复出现传送方块"）
            arenaLevel.setBlock(lobby.offset(2, 1, 3),
                    com.trialportal.registry.ModBlocks.TRIAL_PORTAL.get().defaultBlockState(), 3);
            // 布局重排（㊲）挪过槽位：旧槽位上的装饰（这里自种一根"旧柱"在中央上空）也得跟着清，不能两套并存
            arenaLevel.setBlock(lobby.offset(0, 1, 4),
                    net.minecraft.world.level.block.Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState(), 3);
            int before = countTerminals(arenaLevel, lobby, radius);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, lobby, null);
            int after = countTerminals(arenaLevel, lobby, radius);
            check(failures, before == 6 && after == 5,
                    "多余终端必须在重建时被抹平：埋一个之后应有 6 个，重建后只剩 5 个（实际 "
                            + before + " → " + after + "）");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, 4)).isAir(),
                    "旧布局的残留装饰必须在重建时清掉：中央上空还立着上一版的东西（§9-6 也要求这里空）");
            // 门面（#23 庙 → ㊲ 重排）：门前不许堵、柱子要真立起来、饰带一拱一色、中央依旧空
            int blocked = 0;
            int columns = 0;
            java.util.Set<String> accentColors = new java.util.LinkedHashSet<>();
            for (int dx : new int[] {-arch, 0, arch})
            {
                if (!arenaLevel.getBlockState(lobby.offset(dx, 1, row + 1)).isAir())
                {
                    blocked++;
                }
                for (int dy = 1; dy <= 3; dy++)
                {
                    if (arenaLevel.getBlockState(lobby.offset(dx - 2, dy, row))
                            .is(net.minecraft.world.level.block.Blocks.CHISELED_POLISHED_BLACKSTONE))
                    {
                        columns++;
                    }
                    if (arenaLevel.getBlockState(lobby.offset(dx + 2, dy, row))
                            .is(net.minecraft.world.level.block.Blocks.CHISELED_POLISHED_BLACKSTONE))
                    {
                        columns++;
                    }
                }
                for (int dy = 4; dy <= 5; dy++)
                {
                    accentColors.add(arenaLevel.getBlockState(lobby.offset(dx - 2, dy, row)).getBlock().getName().getString());
                }
            }
            check(failures, blocked == 0, "庙把 " + blocked + " 扇门的门前一格堵住了，玩家右键会点不到门");
            check(failures, columns == 18,
                    "大厅拱门的黑色柱身只数到 " + columns + " 格（三对柱各 3 格 = 18）——装饰没建出来");
            check(failures, accentColors.size() == 3,
                    "三座拱门的主题色饰带应各不相同（现在只有 " + accentColors.size()
                            + " 种）——玩家远远分不清哪扇是哪个玩法");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, south)).getBlock()
                            == com.trialportal.registry.ModBlocks.WARD_PYLON.get()
                            && arenaLevel.getBlockState(lobby.offset(0, 1, south - 1)).isAir(),
                    "南沿正中应立守塔之枢，且塔前一格站得进人（实际 "
                            + arenaLevel.getBlockState(lobby.offset(0, 1, south)).getBlock() + "）");
            int cornerPost = Math.max(2, radius - 2);
            int lamps = 0;
            for (int sx : new int[] {-cornerPost, cornerPost})
            {
                for (int sz : new int[] {-cornerPost, cornerPost})
                {
                    if (arenaLevel.getBlockState(lobby.offset(sx, 2, sz))
                            .is(net.minecraft.world.level.block.Blocks.LANTERN))
                    {
                        lamps++;
                    }
                }
            }
            check(failures, lamps == 4, "台面四角的灯柱只立了 " + lamps + " 根（应 4）");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 0, 0))
                            .is(net.minecraft.world.level.block.Blocks.CHISELED_POLISHED_BLACKSTONE)
                            && arenaLevel.getBlockState(lobby.offset(1, 0, 1))
                                    .is(net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE),
                    "中央徽记应落在 y0（雕纹黑石心 + 镶金角）——中轴步道没铺出来");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, 0)).isAir()
                            && arenaLevel.getBlockState(lobby.offset(0, 3, 0)).isAir()
                            && arenaLevel.getBlockState(lobby.offset(4, 2, 4)).isAir(),
                    "加了庙之后场地中央不再空（§9-6 契约：装饰只许长在边缘）");
            BlockPos cell = com.trialportal.arena.ArenaService.cellOrigin(1);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, cell, TrialMode.CHALLENGE);
            check(failures, arenaLevel.getBlockState(cell.offset(-gap, 1, row)).getBlock() instanceof TrialPortalBlock,
                    "隔间必须有该模式的终端，否则玩家进去就没法继续下一波与结算");
            check(failures, arenaLevel.getBlockState(cell.offset(gap, 1, row)).getBlock()
                            instanceof com.trialportal.block.ArenaExitBlock,
                    "隔间必须有往返门");
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
            // 围栏高度：怪能跳 1 格，一格高的台沿等于没关（也等于玩家可以随手把怪打下虚空）
            int fence = com.trialportal.arena.ArenaService.fenceHeight();
            check(failures, fence >= 2, "围栏至少要 2 格高才关得住会跳的怪，实际 " + fence);
            check(failures, !arenaLevel.getBlockState(trialCell.offset(radius, 1, radius)).isAir()
                            && !arenaLevel.getBlockState(trialCell.offset(radius, fence, radius)).isAir(),
                    "围栏没按配置高度补齐：角上第 1 格或第 " + fence + " 格是空的");
            check(failures, arenaLevel.getBlockState(trialCell.offset(0, fence, radius)).isAir(),
                    "朝房间那侧的门洞只留了 1 格高，走廊接不上");
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

        // 隔间编号按玩法分段：闯关与爬塔永不共用同一块地（用户报的串怪面之一）
        TrialManager accounting = TrialManager.get(server);
        java.util.UUID playerA = java.util.UUID.nameUUIDFromBytes("trial_portal:selftest:a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.UUID playerB = java.util.UUID.nameUUIDFromBytes("trial_portal:selftest:b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cellA = accounting.allocateCell(playerA, TrialMode.CHALLENGE, null);
        int cellB = accounting.allocateCell(playerB, TrialMode.TOWER, null);
        int cellC = accounting.allocateCell(playerA, TrialMode.TRIAL, null);
        check(failures, cellA >= TrialMode.CHALLENGE.cellBase() && cellA < TrialMode.CHALLENGE.cellBase() + 1000
                        && cellB >= TrialMode.TOWER.cellBase() && cellB < TrialMode.TOWER.cellBase() + 1000
                        && cellC >= TrialMode.TRIAL.cellBase(),
                "三种玩法的隔间要各占一段编号（闯关 " + TrialMode.CHALLENGE.cellBase() + " / 爬塔 "
                        + TrialMode.TOWER.cellBase() + " / 试炼 " + TrialMode.TRIAL.cellBase()
                        + "），实际 " + cellA + "、" + cellB + "、" + cellC);
        check(failures, cellA != cellB && cellB != cellC && cellA != cellC,
                "同一时间不同玩法不能拿到同一块地：实际 " + cellA + " / " + cellB + " / " + cellC);
        // 换玩法时旧编号必须从记账里彻底消失——只覆盖不拆，那一间就没人认领了（会串到别人头上）
        check(failures, accounting.cellOf(playerA) == cellC && accounting.cellOf(playerB) == cellB
                        && !accounting.cellsSnapshot().containsValue(cellA),
                "playerA 从闯关换到试炼后，闯关那间（" + cellA + "）的记账没清干净："
                        + accounting.cellsSnapshot());
        // 回返点两条性质：①每次都覆盖（只在缺席时写＝第二次进门前站哪儿就丢了）②场地内的坐标不许写进来
        accounting.recordHome(playerA, new TrialManager.HomePos(Level.OVERWORLD, 10.0D, 64.0D, 10.0D, 0.0F, 0.0F));
        accounting.recordHome(playerA, new TrialManager.HomePos(Level.OVERWORLD, 20.0D, 65.0D, 20.0D, 0.0F, 0.0F));
        TrialManager.HomePos overwritten = accounting.takeHome(playerA);
        check(failures, overwritten != null && overwritten.x() == 20.0D && overwritten.y() == 65.0D,
                "第二次 recordHome 没覆盖掉第一次的（回返点还停在 " + overwritten + "）");
        accounting.recordHome(playerA, new TrialManager.HomePos(Level.OVERWORLD, 30.0D, 66.0D, 30.0D, 0.0F, 0.0F));
        accounting.recordHome(playerA, new TrialManager.HomePos(com.trialportal.arena.ArenaService.ARENA,
                0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
        TrialManager.HomePos home = accounting.takeHome(playerA);
        check(failures, home != null && home.dimension() == Level.OVERWORLD && home.x() == 30.0D,
                "场地坐标被当成回返点记进去了（回返点落在场地上＝把人送回虚空）：实际 " + home);
        // 维度没加载时不许顺手销掉隔间编号：编号一丢，那间房就永远没人认领（开服清扫也找不到它）
        accounting.recordHome(playerA, new TrialManager.HomePos(Level.OVERWORLD, 40.0D, 64.0D, 40.0D, 0.0F, 0.0F));
        int held = accounting.cellOf(playerA);
        com.trialportal.arena.ArenaService.releaseCell(null, accounting, playerA, held);
        check(failures, accounting.cellOf(playerA) == held,
                "维度没加载时 releaseCell 还是把编号 " + held + " 销了，那间房从此没人认领");
        check(failures, accounting.takeHome(playerA) != null, "releaseCell 不该顺手把回返点也吃掉");
        accounting.freeCell(playerA);
        accounting.freeCell(playerB);

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

        // 资源副本库（CHALLENGE 玩法的唯一真相，不再从 trials/ 取波次）
        check(failures, ChallengeLibrary.ids().size() >= 2,
                "自带副本至少 2 份，实际 " + ChallengeLibrary.ids().size());
        for (ChallengeDefinition challenge : ChallengeLibrary.ordered())
        {
            check(failures, challenge.difficulties.keySet().containsAll(ChallengeDefinition.DIFFICULTY_KEYS)
                            && challenge.difficulties.size() == 3,
                    challenge.id() + " 必须恰好三档难度 easy/normal/hard，实际 " + challenge.difficulties.keySet());
            ChallengeDefinition.Difficulty easy = challenge.difficulty("easy");
            ChallengeDefinition.Difficulty hard = challenge.difficulty("hard");
            double easyHp = easy == null || easy.strength == null ? 1.0D : easy.strength.healthMultiplier;
            double hardHp = hard == null || hard.strength == null ? 1.0D : hard.strength.healthMultiplier;
            check(failures, easy != null && hard != null && hard.mobCap > easy.mobCap && hardHp >= easyHp,
                    challenge.id() + " 的 hard 要比 easy 更凶（场上上限更高、血量倍率不许更低）");
            check(failures, challenge.defaultDifficulty() != null, challenge.id() + " 要能取到默认难度");
        }
        check(failures, "normal".equals(ChallengeLibrary.normalizeDifficulty(null))
                        && "hard".equals(ChallengeLibrary.normalizeDifficulty(" HARD "))
                        && ChallengeLibrary.normalizeDifficulty("nightmare") == null,
                "难度键归一化：缺省=normal、大小写与空格要容忍、不认识的键要返回 null 而不是猜");

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
                            arenaLevel, room, roomOrigin, net.minecraft.util.RandomSource.create(4321L),
                            room.paletteFor(4321L));
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
                    // 遗留怪：开局前那次是兜底，真正的清怪时机是离场拆台那一次（整场区块一直强制加载，看得见）。
                    // 这里只验盒子盖得住地；怪真被清掉没有要等世界真的 tick 起来才查得到（见 onServerTick 的延后段）。
                    BlockPos sweepOrigin = com.trialportal.arena.ArenaService.cellOrigin(8);
                    com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, sweepOrigin, TrialMode.CHALLENGE);
                    net.minecraft.world.phys.AABB sweepBox = com.trialportal.arena.ArenaService.cellBox(8, 0);
                    net.minecraft.world.phys.AABB deepBox = com.trialportal.arena.ArenaService.cellBox(8, 44);
                    check(failures, sweepBox.contains(sweepOrigin.getX() + 0.5D, sweepOrigin.getY() + 1.0D,
                                    sweepOrigin.getZ() + 0.5D)
                                    && sweepBox.contains(sweepOrigin.getX() - radius, sweepOrigin.getY(),
                                    sweepOrigin.getZ() + radius),
                            "扫怪盒子没盖住平台本身（中心或台沿落在盒子外）");
                    check(failures, deepBox.contains(sweepOrigin.getX(), sweepOrigin.getY() + 1,
                                    sweepOrigin.getZ() + 44 + radius),
                            "扫怪盒子没跟着进深长大：建到 44 格外的房里的怪会漏网");
                    com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 8);
                    // 真离场走的是 releaseCell(uuid)，进深要从记账里来——真机就是因为这里漏传留下一整间房
                    probeReleaseByAccounting(arenaLevel, server, failures, radius);
                    // 换玩法（编号跨段）时旧那间必须真被拆掉，不是只把记账覆盖掉
                    probeModeSwitchReclaimsOldCell(arenaLevel, server, failures, radius);
                    // 拆场按真实写过的区域算，不按房间表声明的尺寸猜
                    probeTeardownUsesRecordedBox(arenaLevel, server, failures);
                    // 灯要真嵌进墙环，拆场也得一起没
                    probeRoomLights(arenaLevel, server, failures);
                    // 立柱顶到天花板 + BOSS 锚点落在中心舞台
                    probePillarsAndBossAnchor(arenaLevel, failures);
                    // 房间引用的方块必须真的存在（离线门看不到注册表）
                    probeRoomBlocksResolve(failures);
                    // 同一颗种子建出同一份布局
                    probeLayoutIsSeedDriven(arenaLevel, failures);
                    // 外观变体按种子挑、花纹落地、拆场清得干净
                    probeAppearanceVariants(arenaLevel, failures);
                    // 落点布局：横移过的房要接得住、拆得净，顺手核直走廊的退化形状
                    probeJoggedChainWalksThrough(arenaLevel, failures);
                    // layouts 的两条校验要有牙
                    probeLayoutRulesRejected(failures);
                    // 外观×落点的组合要跑满，不能被锁成固定那几对
                    probeVariantLayoutPairing(failures);
                    // 房内隔断：砌得出来、封不死房间、拆得干净
                    probePartitions(arenaLevel, failures);
                    probePartitionRulesRejected(failures);
                    // 开服清扫两步之后，回返点还得留着（它跑在玩家登录之前）
                    // 守塔的塔：注册、入口身份、结界铺面与四面门、血量池、拆台归还
                    probeWardPylon(server, arenaLevel, failures);
                    // 结界外缘落点：踩在台面上、落在围栏里、不挤在塔脚下
                    probeDefenseEdgeSpawn(server, arenaLevel, failures);
                    // 拆塔那只怪：分派比例 + goal 真扣血的对账
                    probeTowerAssault(server, arenaLevel, failures);
                    probeHomeSurvivesStartupSweep(server, arenaLevel, failures);
                }
            }
        }

        // 通道只有往返门一条：玩法门不许有配方、往返门必须恰好有一条（§9 第 12 条）
        probePassageRecipes(server, failures);
        // 野外那台拆得掉，就要掉得出自己：战利品表按原版约定挂名，漏了不报错
        probePortalBlocksDropThemselves(server, failures);
        // 这一台通道的方向（在野外=进去，在场地=出来）
        probeReturnPassage(failures);
        // 关卡的 mobs[].count 是配比，不是写了没人读的摆设
        probeChallengeSpawnPlan(failures);
        // 数据里写的怪名要真在注册表里：找不到只会 warn 一句然后跳过，那场就打不完
        probeMobIdsResolve(server, failures);
        // 守塔内容与各玩法编号段
        probeDefenseDefinitions(server, failures);
        // 守塔的波次阶梯与通关那条件
        probeDefenseWaveLadder(failures);
        // 守塔状态机四条分支（塔破必须赢过判赢）
        probeDefenseSteps(failures);
        // 创造配置口的唯一写入口：坏值回滚、错 id 当场拒、副本不许写穿库
        probeDefenseEdit(server, failures);

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
        // 守塔的配置口也得真能执行：子树没注册时 brigadier 只会返回 0，光查名字查不出来
        try
        {
            int defenses = server.getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), "tei defense list");
            check(failures, defenses == DefenseLibrary.ids().size(),
                    "/tei defense list 应回报 " + DefenseLibrary.ids().size() + " 份守塔定义，实际 " + defenses);
        }
        catch (Exception exception)
        {
            failures.add("/tei defense list 执行失败：" + exception);
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

        // 强度必须是 permanent modifier（会随实体存档），且不许改基值——
        // 以前 scaleForFloor 用 setBaseValue 乘基值，SNBT 写的属性会被它吃掉，顺序决定结果。
        Vindicator dungeonMob = new Vindicator(EntityType.VINDICATOR, level);
        double plainHealth = dungeonMob.getMaxHealth();
        double plainHealthBase = dungeonMob.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        double plainArmor = dungeonMob.getAttribute(Attributes.ARMOR).getValue();
        MobStrength strength = new MobStrength();
        strength.healthMultiplier = 2.0D;
        strength.damageMultiplier = 1.5D;
        strength.armorBonus = 3.0D;
        MobStrengths.apply(dungeonMob, strength);
        check(failures, Math.abs(dungeonMob.getMaxHealth() - plainHealth * 2.0D) < 0.51D,
                "strength.health_multiplier=2 应把上限从 " + plainHealth + " 抬到 " + plainHealth * 2.0D
                        + "，实际 " + dungeonMob.getMaxHealth());
        check(failures, Math.abs(dungeonMob.getAttribute(Attributes.MAX_HEALTH).getBaseValue() - plainHealthBase) < 0.01D,
                "倍率不该改基值（改了就会吃掉 SNBT 里写的属性）");
        check(failures, Math.abs(dungeonMob.getAttribute(Attributes.ARMOR).getValue() - (plainArmor + 3.0D)) < 0.01D,
                "armor_bonus 应加 3 点，实际 " + dungeonMob.getAttribute(Attributes.ARMOR).getValue());
        MobStrengths.apply(dungeonMob, strength);
        check(failures, Math.abs(dungeonMob.getMaxHealth() - plainHealth * 2.0D) < 0.51D,
                "同一份强度重复应用不该叠层（每条属性一个固定 UUID）");
        CompoundTag dungeonSaved = new CompoundTag();
        dungeonMob.saveWithoutId(dungeonSaved);
        Vindicator dungeonRestored = new Vindicator(EntityType.VINDICATOR, level);
        dungeonRestored.load(dungeonSaved);
        check(failures, Math.abs(dungeonRestored.getMaxHealth() - dungeonMob.getMaxHealth()) < 0.51D,
                "重载后强度倍率应还在（不能因为区块卸载掉回原值）");
        dungeonMob.discard();

        // 三层链：定义级 → 波次级 → 单只怪级，乘数相乘、加值相加
        MobStrength outer = new MobStrength();
        outer.healthMultiplier = 2.0D;
        outer.armorBonus = 1.0D;
        MobStrength inner = new MobStrength();
        inner.healthMultiplier = 1.5D;
        inner.damageMultiplier = 3.0D;
        inner.armorBonus = 2.0D;
        MobStrength chain = MobStrength.combined(outer, inner);
        check(failures, chain != null && Math.abs(chain.healthMultiplier - 3.0D) < 0.0001D
                        && Math.abs(chain.damageMultiplier - 3.0D) < 0.0001D
                        && Math.abs(chain.armorBonus - 3.0D) < 0.0001D,
                "强度链应乘数相乘、加值相加，实际 health=" + (chain == null ? "null" : chain.healthMultiplier)
                        + " damage=" + (chain == null ? "null" : chain.damageMultiplier)
                        + " armor=" + (chain == null ? "null" : chain.armorBonus));
        check(failures, MobStrength.combined(null, inner) == inner && MobStrength.combined(outer, null) == outer,
                "链上任一层缺省就该原样透传");
        check(failures, new MobStrength().isIdentity() && MobStrength.perFloor(1.0D, 1.0D).isIdentity(),
                "没写强度时必须是恒等（不许悄悄给所有怪加血）");
        MobStrength bogus = new MobStrength();
        bogus.healthMultiplier = -1.0D;
        check(failures, bogus.resolve("测试") != null, "负倍率必须被校验拦下");
        MobStrength percent = new MobStrength();
        percent.damageMultiplier = 250.0D;
        check(failures, percent.resolve("测试") != null,
                "倍率大得不像话（像是写成百分比）必须被校验拦下");
        check(failures, TrialMode.CHALLENGE.isSustained() && !TrialMode.TRIAL.isSustained()
                        && !TrialMode.TOWER.isSustained(),
                "只有闯关是「不计波次、持续补充」的玩法");

        int interval = TrialConfig.GENERAL_TICK_INTERVAL.get();
        check(failures, interval >= 1, "tick_interval 至少为 1");
        boss.discard();
        return failures;
    }

    /** 失败信息里把一波的内容摘要出来，免得只看到"没按编排出来"却不知道实际是什么。 */
    private static String describe(TrialDefinition.WaveContent content)
    {
        if (content == null)
        {
            return "null";
        }
        StringBuilder text = new StringBuilder(content.title == null ? "?" : content.title).append(" → ");
        for (TrialDefinition.MobEntry entry : content.mobs)
        {
            text.append(entry.entity).append('x').append(entry.count).append(entry.boss ? "(BOSS)" : "").append(' ');
        }
        return text.toString().trim();
    }

    /** 造一份只用来测校验的合成爬塔定义：floors 由调用方填。 */
    private static TrialDefinition probeTowerWithFloors(int coveredTo, TrialDefinition.FloorOverride... overrides)
    {
        TrialDefinition definition = new TrialDefinition();
        definition.mode = TrialMode.TOWER;
        definition.displayName = "探针塔";
        TrialDefinition.Tier tier = new TrialDefinition.Tier();
        tier.from = 1;
        tier.to = coveredTo;
        tier.mobs = List.of("minecraft:zombie");
        TrialDefinition.ScalingRule scaling = new TrialDefinition.ScalingRule();
        scaling.tiers = new ArrayList<>(List.of(tier));
        scaling.floors = new ArrayList<>(List.of(overrides));
        definition.scaling = scaling;
        return definition;
    }

    private static TrialDefinition.FloorOverride probeFloor(int floor, String entity)
    {
        TrialDefinition.MobEntry entry = new TrialDefinition.MobEntry();
        entry.entity = entity;
        entry.count = 1;
        TrialDefinition.FloorOverride override = new TrialDefinition.FloorOverride();
        override.floor = floor;
        override.mobs = new ArrayList<>(List.of(entry));
        return override;
    }

    private static boolean probeDuplicateFloorRejected()
    {
        TrialDefinition definition = probeTowerWithFloors(9999, probeFloor(7, "minecraft:zombie"),
                probeFloor(7, "minecraft:creeper"));
        String error = definition.resolve("trial_portal:__probe_dup");
        return error != null && error.contains("配了两次");
    }

    private static boolean probeFloorBeyondTiersRejected()
    {
        TrialDefinition definition = probeTowerWithFloors(50, probeFloor(88, "minecraft:zombie"));
        return definition.resolve("trial_portal:__probe_far") != null;
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

    /** 台面范围内还站着多少个功能终端（传送门 / 返回）。 */
    private static int countTerminals(ServerLevel level, BlockPos origin, int radius)
    {
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dz = -radius; dz <= radius; dz++)
            {
                for (int dy = 1; dy <= 2; dy++)
                {
                    var block = level.getBlockState(origin.offset(dx, dy, dz)).getBlock();
                    if (block instanceof TrialPortalBlock
                            || block instanceof com.trialportal.block.ArenaExitBlock)
                    {
                        count++;
                    }
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
                    net.minecraft.util.RandomSource.create(777L), probe.paletteFor(777L));
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
     * 换玩法要跨编号段（闯关 1 起、试炼 2001 起）。旧编号要是只被 {@code put} 覆盖掉，记账里就再没人
     * 记得那儿有一间房：平台留在虚空里，而那个编号重新变成"空闲"，下一批人里谁分到它就会走进别人的旧场地。
     * 这条只认方块——实体在无头门里查不到（见 {@link #onServerStarted} 的说明）。
     */
    private static void probeModeSwitchReclaimsOldCell(ServerLevel arenaLevel, MinecraftServer server,
                                                        List<String> failures, int radius)
    {
        TrialManager manager = TrialManager.get(server);
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:swap".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int oldCell = manager.allocateCell(player, TrialMode.CHALLENGE, null);
        BlockPos oldOrigin = com.trialportal.arena.ArenaService.cellOrigin(oldCell);
        com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, oldOrigin, TrialMode.CHALLENGE);
        int built = countPlatformBlocks(arenaLevel, oldOrigin, radius);
        // 上一间房建出去多远、真实写过哪些格子，都得先记在账上——不记就等于让"清零"这条判据
        // 在没有旧值的情况下跑，怎么注入都是绿（本轮第一版就是这么空的）。
        BlockPos oldTail = oldOrigin.offset(0, 0, radius + 12);
        arenaLevel.setBlock(oldTail, net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        manager.recordBuilt(player, 12, new net.minecraft.world.phys.AABB(oldOrigin.getX() - 1, oldOrigin.getY(),
                oldOrigin.getZ() - 1, oldOrigin.getX() + 2D, oldOrigin.getY() + 2D, oldTail.getZ() + 2D));
        check(failures, manager.reachOf(player) == 12 && manager.builtBoxOf(player) != null,
                "换段探针没能把旧隔间的进深/足迹记上账，后面的清零判据会在空数据上跑绿");
        int newCell = manager.allocateCell(player, TrialMode.TRIAL, arenaLevel);
        check(failures, built > 0 && newCell != oldCell,
                "探针没能造出旧隔间（建了 " + built + " 块，跨段新编号 " + newCell + "，旧编号 " + oldCell + "）");
        int leftAfter = countPlatformBlocks(arenaLevel, oldOrigin, radius);
        check(failures, leftAfter == 0,
                "换玩法后旧隔间 " + oldCell + " 还留着 " + leftAfter + " 块平台：编号会被重新分给别的玩家");
        // 进深与足迹属于"那一间 cell"，不属于玩家。留着就会串到新隔间：recordBuilt 取并集之后，
        // 新间的拆场盒子横跨两个分段（相差 2000 个编号 ≈ 32 万格），主线程遍历几十万个位置，
        // 还会把沿途别人的台子一起 AIR 掉。
        check(failures, manager.reachOf(player) == 0 && manager.builtBoxOf(player) == null,
                "换段之后玩家的进深/足迹没清零（reach=" + manager.reachOf(player)
                        + " built=" + manager.builtBoxOf(player) + "），新隔间会继承旧隔间的拆场盒子");
        // 装饰超出声明尺寸时，换段那一次拆台必须按真实足迹来（三参重载不传 built 的旧写法会留下残骸）
        java.util.UUID tailPlayer = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:swap_tail".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int tailCell = manager.allocateCell(tailPlayer, TrialMode.CHALLENGE, null);
        BlockPos tailOrigin = com.trialportal.arena.ArenaService.cellOrigin(tailCell);
        com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, tailOrigin, TrialMode.CHALLENGE);
        BlockPos tailDecor = tailOrigin.offset(com.trialportal.arena.ArenaService.radius() + 3, 0, 0);
        arenaLevel.setBlock(tailDecor, net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        manager.recordBuilt(tailPlayer, 0, new net.minecraft.world.phys.AABB(tailDecor.getX(), tailDecor.getY(),
                tailDecor.getZ(), tailDecor.getX() + 1D, tailDecor.getY() + 1D, tailDecor.getZ() + 1D));
        manager.allocateCell(tailPlayer, TrialMode.TRIAL, arenaLevel);
        check(failures, arenaLevel.getBlockState(tailDecor).isAir(),
                "换段拆台没按真实足迹清：超出声明尺寸的装饰（" + tailDecor.toShortString() + "）还留在虚空里");
        manager.freeCell(tailPlayer);
        manager.freeCell(player);
        probeCellNumberStaysInsideSegment(manager, failures);
    }

    /**
     * 编号不许越段：一段 1000 个格子，填满之后必须拒发，而不是摸进下一个分段——
     * 两个玩家落在同一个 cell 上时，一方拆台就把另一方的场地与怪一起清掉。
     * 这里"填到拒绝为止"而不是"正好填 1000 个"：本 JVM 里前面几条判法已经占了几个编号。
     */
    private static void probeCellNumberStaysInsideSegment(TrialManager manager, List<String> failures)
    {
        int base = TrialMode.TOWER.cellBase();
        int top = base + TrialManager.CELLS_PER_MODE;
        List<java.util.UUID> filled = new ArrayList<>();
        boolean refused = false;
        for (int i = 0; i < TrialManager.CELLS_PER_MODE + 8 && !refused; i++)
        {
            java.util.UUID fake = java.util.UUID.nameUUIDFromBytes(
                    ("trial_portal:selftest:flood:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int cell = manager.allocateCell(fake, TrialMode.TOWER, null);
            if (cell < 0)
            {
                refused = true;
                break;
            }
            if (cell < base || cell >= top)
            {
                failures.add("第 " + i + " 个假玩家拿到了越段编号 " + cell + "（段区间 " + base + "~"
                        + (top - 1) + "）：两个人会共占一块地");
                return;
            }
            filled.add(fake);
        }
        check(failures, refused,
                "填满一段编号之后还在继续发（只发出 " + filled.size() + " 个就被我停住了），越段迟早发生");
        for (java.util.UUID fake : filled)
        {
            manager.freeCell(fake);
        }
    }

    /**
     * 灯：房间表的 {@code light_block}/{@code light_spacing} 必须真的嵌进墙环，并且拆场时一起没。
     * 这条是玩家报的第一条观感问题的落地判据——维度 ambient_light 只有 0.15，没灯的房等于黑盒子。
     */
    private static void probeRoomLights(ServerLevel arenaLevel, MinecraftServer server, List<String> failures)
    {
        TrialManager manager = TrialManager.get(server);
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:light".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(player, TrialMode.TRIAL, null);
        RoomDefinition room = new RoomDefinition();
        room.size = List.of(7, 4, 7);
        room.spawnPoints = 1;
                room.lightBlock = "minecraft:soul_lantern";
        room.lightSpacing = 2;
        String error = room.resolve("trial_portal:__probe_light");
        if (error != null)
        {
            check(failures, false, "灯探针没过自己的校验（" + error + "），这条判据没跑");
            return;
        }
        BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        BlockPos origin = cellOrigin.offset(-room.width() / 2, 0, com.trialportal.arena.ArenaService.radius() + 4);
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, origin,
                net.minecraft.util.RandomSource.create(4711L), room.paletteFor(4711L));
        int lamps = countLamps(arenaLevel, origin, room);
        check(failures, lamps >= 4,
                "墙环上只找到 " + lamps + " 盏灯（light_spacing=2 的 7x7 房该有一排）——灯没嵌进墙里，房还是黑的");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell,
                origin.getZ() + room.depth() - cellOrigin.getZ(), null);
        check(failures, countLamps(arenaLevel, origin, room) == 0, "拆场把灯落在了虚空里");
        manager.freeCell(player);
    }

    /** 数墙环视线那一层（dy=2）上有多少盏探针灯。 */
    private static int countLamps(ServerLevel level, BlockPos origin, RoomDefinition room)
    {
        int lamps = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-1, 2, -1),
                origin.offset(room.width(), 2, room.depth())))
        {
            if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SOUL_LANTERN))
            {
                lamps++;
            }
        }
        return lamps;
    }

    /**
     * 立柱要顶到天花板（以前写死 4 格，8 格高的厅里就是一根半截桩），
     * BOSS 锚点要落在房间中心那块可站的地上（它以前是死代码，BOSS 实际跟小怪一起排在内圈门边）。
     */
    private static void probePillarsAndBossAnchor(ServerLevel arenaLevel, List<String> failures)
    {
        RoomDefinition room = new RoomDefinition();
        room.size = List.of(7, 8, 7);
        room.spawnPoints = 1;
                room.pillars = 1;
        room.pillarBlock = "minecraft:gilded_blackstone";
        room.bossRoom = true;
        String error = room.resolve("trial_portal:__probe_pillar");
        if (error != null)
        {
            check(failures, false, "立柱探针没过自己的校验（" + error + "），这条判据没跑");
            return;
        }
        BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(9);
        BlockPos origin = cellOrigin.offset(-room.width() / 2, 0, com.trialportal.arena.ArenaService.radius() + 4);
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, origin,
                net.minecraft.util.RandomSource.create(1313L), room.paletteFor(1313L));
        BlockPos column = origin.offset(2, 1, 2);
        int tall = 0;
        for (int dy = 1; dy <= room.height(); dy++)
        {
            if (arenaLevel.getBlockState(column.above(dy - 1))
                    .is(net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE))
            {
                tall++;
            }
        }
        check(failures, tall == room.height(),
                "8 格高的厅里柱子只有 " + tall + " 格（声明 pillar_height=0 应顶到天花板，材质也要认 pillar_block）");
        check(failures, arenaLevel.getBlockState(column.above(room.height())).isAir(),
                "柱子穿过了声明高度");
        BlockPos anchor = room.bossAnchor(origin);
        check(failures, isGround(arenaLevel, anchor.below()) && arenaLevel.getBlockState(anchor).isAir()
                        && arenaLevel.getBlockState(anchor.above()).isAir(),
                "BOSS 锚点 " + anchor.toShortString() + " 不是能站两个人的净空（脚下不实心或头顶被挡）");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 9,
                origin.getZ() + room.depth() - cellOrigin.getZ());
    }

    /**
     * 外观变体（#24）：同一间房按会话种子换材质，几何与站怪点不许变；花纹要真的落到地板上；
     * 拆场必须把变体材质一起清干净（笔刷记的就是真实足迹）。
     */
    private static void probeAppearanceVariants(ServerLevel arenaLevel, List<String> failures)
    {
        RoomDefinition room = RoomLibrary.get("trial_portal:hall_small");
        if (room == null || room.variants.size() < 2)
        {
            check(failures, false, "小厅没配至少两套 variants，外观多样性判据没跑");
            return;
        }
        RoomDefinition.Palette first = room.paletteFor(0L);
        RoomDefinition.Palette second = room.paletteFor(1L);
        check(failures, room.paletteFor(0L).floor.equals(first.floor)
                        && room.paletteFor(0L).wall.equals(first.wall),
                "同一颗种子必须挑到同一套外观（玩家能背板，不能每次进都换一套）");
        check(failures, !first.floor.equals(second.floor) || !first.wall.equals(second.wall),
                "两颗相邻种子挑出了同一套外观：paletteFor 没按种子取模");
        check(failures, !first.floor.equals(room.floor) || !second.floor.equals(room.floor),
                "变体没覆盖房间默认材质（floor 仍是房间自己那份）");

        int cellA = 14;
        int cellB = 15;
        int offset = com.trialportal.arena.ArenaService.radius() + 4;
        BlockPos originA = com.trialportal.arena.ArenaService.cellOrigin(cellA).offset(-room.width() / 2, 0, offset);
        BlockPos originB = com.trialportal.arena.ArenaService.cellOrigin(cellB).offset(-room.width() / 2, 0, offset);
        com.trialportal.arena.RoomBuilder.Built builtA = com.trialportal.arena.RoomBuilder.build(arenaLevel, room,
                originA, net.minecraft.util.RandomSource.create(2L), first);
        com.trialportal.arena.RoomBuilder.Built builtB = com.trialportal.arena.RoomBuilder.build(arenaLevel, room,
                originB, net.minecraft.util.RandomSource.create(2L), second);
        check(failures, !arenaLevel.getBlockState(originA.offset(1, 0, 1)).isAir()
                        && !arenaLevel.getBlockState(originA.offset(1, 0, 1))
                        .equals(arenaLevel.getBlockState(originB.offset(1, 0, 1))),
                "两套外观铺出来的地板是同一块方块（变体没生效）");
        check(failures, builtA.spawnPoints().size() == builtB.spawnPoints().size()
                        && builtA.region().getXsize() == builtB.region().getXsize(),
                "换外观不该动几何：站怪点数量或房间尺寸变了");
        int accent = 0;
        for (int dx = 0; dx <= room.width() + 1; dx++)
        {
            for (int dz = 0; dz <= room.depth() + 1; dz++)
            {
                if (arenaLevel.getBlockState(originA.offset(dx - 1, 0, dz - 1))
                        .is(net.minecraft.world.level.block.Blocks.CHISELED_POLISHED_BLACKSTONE))
                {
                    accent++;
                }
            }
        }
        check(failures, accent > 0, "border 花纹一道都没铺出来（floor_pattern 或 pattern_block 没接上）");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cellA,
                originA.getZ() + room.depth() - com.trialportal.arena.ArenaService.cellOrigin(cellA).getZ(),
                builtA.region());
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cellB,
                originB.getZ() + room.depth() - com.trialportal.arena.ArenaService.cellOrigin(cellB).getZ(),
                builtB.region());
        check(failures, countRoomBlocks(arenaLevel, originA, room) == 0
                        && countRoomBlocks(arenaLevel, originB, room) == 0,
                "换外观之后拆场清不干净（变体材质留在虚空里）");
    }

    /**
     * 落点布局（R18 ⑤）：把一间横移过的房接到上一间后面。
     * 跑两套间隙——作者实际配的那档，和校验放行的最小那档（边界最容易让新房外墙把横移段封死）。
     */
    private static void probeJoggedChainWalksThrough(ServerLevel arenaLevel, List<String> failures)
    {
        RoomDefinition room = RoomLibrary.get("trial_portal:hall_small");
        RoomDefinition.Layout jog = firstJoggedLayout(room);
        if (jog == null)
        {
            check(failures, false, "小厅没配带 lateral 的 layouts，落点多样性的判据根本没跑");
            return;
        }
        probeJoggedChain(arenaLevel, failures, room, jog.lateral, jog.gap, 16, "作者配的间隙");
        probeJoggedChain(arenaLevel, failures, room, jog.lateral, RoomDefinition.Layout.MIN_GAP_FOR_JOG, 18,
                "校验放行的最小间隙");
        probeStraightLinkShape(arenaLevel, failures);
        probeLinkLaneIsStandable(arenaLevel, failures, lateralOf(jog));
    }

    /** 与生产同一个夹取口径，别让判据跑在一个永远到不了的横移量上。 */
    private static int lateralOf(RoomDefinition.Layout jog)
    {
        int limit = com.trialportal.arena.ArenaService.lateralLimit(9);
        return Math.max(-limit, Math.min(limit, jog.lateral));
    }

    /**
     * 走廊单独建在空隔间里的性质：铺了地板的每一格都必须站得住人。
     * 这条专门盯"挡墙落进车道"——拐角处两段车道会互相穿过对方的墙位，先写墙就会被自己封死。
     * 单独建而不连着房建，是因为房里的遮挡物会随机占格，混在一起就成了碰运气的断言。
     */
    private static void probeLinkLaneIsStandable(ServerLevel arenaLevel, List<String> failures, int lateral)
    {
        int cell = 19;
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        int zFrom = origin.getZ() + com.trialportal.arena.ArenaService.radius() + 4;
        net.minecraft.world.phys.AABB straight = com.trialportal.arena.RoomBuilder.buildLink(arenaLevel,
                origin.getX(), zFrom, origin.getX(), zFrom + 8, com.trialportal.arena.RoomBuilder.entryFloor(),
                com.trialportal.arena.RoomBuilder.entryWall(), com.trialportal.arena.RoomBuilder.defaultLight(),
                com.trialportal.arena.RoomBuilder.defaultLightSpacing());
        net.minecraft.world.phys.AABB jogged = com.trialportal.arena.RoomBuilder.buildLink(arenaLevel,
                origin.getX(), zFrom + 12, origin.getX() + lateral, zFrom + 20,
                com.trialportal.arena.RoomBuilder.entryFloor(), com.trialportal.arena.RoomBuilder.entryWall(),
                com.trialportal.arena.RoomBuilder.defaultLight(),
                com.trialportal.arena.RoomBuilder.defaultLightSpacing());
        check(failures, flooredCells(arenaLevel, straight) >= 3 * 10,
                "直走廊铺出来的地板只有 " + flooredCells(arenaLevel, straight) + " 格，不像一条 3 格宽的通道");
        check(failures, blockedLaneCells(arenaLevel, straight) == 0,
                "直走廊有 " + blockedLaneCells(arenaLevel, straight) + " 格地板被墙挡住");
        check(failures, blockedLaneCells(arenaLevel, jogged) == 0,
                "折线走廊拐角有 " + blockedLaneCells(arenaLevel, jogged) + " 格地板被自己的挡墙挡住");
        check(failures, canWalkAlong(arenaLevel, origin.getX(), zFrom + 12, origin.getX() + lateral, zFrom + 13)
                        && canWalkAlong(arenaLevel, origin.getX() + lateral, zFrom + 13, origin.getX() + lateral,
                        zFrom + 20),
                "折线走廊的拐角走不通：横移段与纵向段之间断了");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell, zFrom + 21 - origin.getZ(),
                straight.minmax(jogged));
        check(failures, flooredCells(arenaLevel, jogged) == 0 && flooredCells(arenaLevel, straight) == 0,
                "拆场没把两条走廊的地板还回虚空");
    }

    /** 一条线上有地板的格数（用来证明走廊真的铺了东西，不是判据空转）。 */
    private static int flooredCells(ServerLevel level, net.minecraft.world.phys.AABB area)
    {
        int floorY = com.trialportal.arena.ArenaService.FLOOR_Y;
        int count = 0;
        for (int x = (int) Math.floor(area.minX); x < (int) Math.ceil(area.maxX); x++)
        {
            for (int z = (int) Math.floor(area.minZ); z < (int) Math.ceil(area.maxZ); z++)
            {
                if (isGround(level, new BlockPos(x, floorY, z)))
                {
                    count++;
                }
            }
        }
        return count;
    }

    /** 走廊上两点之间走得到吗（不掺房屋，免得遮挡物把判据变成碰运气）。 */
    private static boolean canWalkAlong(ServerLevel level, int fromX, int fromZ, int toX, int toZ)
    {
        int floorY = com.trialportal.arena.ArenaService.FLOOR_Y;
        List<BlockPos> start = List.of(new BlockPos(fromX, floorY, fromZ));
        return reachable(level, start, toX, toZ,
                Math.min(fromX, toX) - 6, Math.max(fromX, toX) + 6,
                Math.min(fromZ, toZ) - 6, Math.max(fromZ, toZ) + 6);
    }

    /**
     * 洪水填充：从一批起点出发能不能踩到目标格。可走 = "脚下实心 + 上身两格空"。
     * 范围钉在给定盒子里，不会把整张虚空图漫一遍。
     */
    private static boolean reachable(ServerLevel level, List<BlockPos> starts, int toX, int toZ,
                                     int xLo, int xHi, int zLo, int zHi)
    {
        return floodFill(level, starts, xLo, xHi, zLo, zHi).contains(key(toX, toZ));
    }

    /** 从一批起点做洪水填充，返回走得到的所有格。可走 = "脚下实心 + 上身两格空"。 */
    private static Set<Long> floodFill(ServerLevel level, List<BlockPos> starts,
                                       int xLo, int xHi, int zLo, int zHi)
    {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        for (BlockPos start : starts)
        {
            if (standable(level, start) && seen.add(key(start.getX(), start.getZ())))
            {
                queue.add(new long[] {start.getX(), start.getZ()});
            }
        }
        while (!queue.isEmpty())
        {
            long[] here = queue.poll();
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            {
                int nx = (int) here[0] + step[0];
                int nz = (int) here[1] + step[1];
                if (nx < xLo || nx > xHi || nz < zLo || nz > zHi || !seen.add(key(nx, nz)))
                {
                    continue;
                }
                if (standable(level, new BlockPos(nx, com.trialportal.arena.ArenaService.FLOOR_Y, nz)))
                {
                    queue.add(new long[] {nx, nz});
                }
            }
        }
        return seen;
    }

    /**
     * 房内隔断（㉕）最要紧的一条判据：<b>房里每一格能站人的地方，都必须从门洞走得到</b>。
     * 封出不通的小间就意味着怪卡在里面、玩家永远打不到，波次清不完——这一格是不是隔断压出来的小间，
     * 用一次从门洞出发的填充就能数出来，不需要知道隔断怎么画的（不复刻画法，见上面的教训）。
     */
    private static int sealedStandableCells(ServerLevel level, BlockPos origin, RoomDefinition room)
    {
        int doorX = origin.getX() + room.width() / 2;
        int doorZ = origin.getZ() - 1;
        List<BlockPos> fromDoor = List.of(new BlockPos(doorX, com.trialportal.arena.ArenaService.FLOOR_Y, doorZ));
        Set<Long> reached = floodFill(level, fromDoor, origin.getX() - 1, origin.getX() + room.width(),
                doorZ, origin.getZ() + room.depth());
        int sealed = 0;
        for (int dx = 0; dx < room.width(); dx++)
        {
            for (int dz = 0; dz < room.depth(); dz++)
            {
                BlockPos cell = origin.offset(dx, 0, dz);
                if (standable(level, cell) && !reached.contains(key(cell.getX(), cell.getZ())))
                {
                    sealed++;
                }
            }
        }
        return sealed;
    }

    /** 逐间隔断房：隔断真的砌出来了、房里没有封死的小间、拆场也把它们一起带走。 */
    private static void probePartitions(ServerLevel arenaLevel, List<String> failures)
    {
        int withPartitions = 0;
        int cell = 20;
        for (RoomDefinition room : RoomLibrary.snapshot().values())
        {
            if (room.partitions.isEmpty())
            {
                continue;
            }
            withPartitions++;
            BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(cell);
            BlockPos origin = cellOrigin.offset(-room.width() / 2, 0,
                    com.trialportal.arena.ArenaService.radius() + 4);
            com.trialportal.arena.RoomBuilder.Built built = com.trialportal.arena.RoomBuilder.build(
                    arenaLevel, room, origin, net.minecraft.util.RandomSource.create(cell * 7L),
                    room.paletteFor(cell));
            int missing = 0;
            for (RoomDefinition.Partition partition : room.partitions)
            {
                net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation
                        .tryParse(partition.block);
                net.minecraft.world.level.block.Block expected = id == null ? null
                        : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
                List<int[]> cells = partition.cells();
                BlockPos foot = origin.offset(cells.get(0)[0], 1, cells.get(0)[1]);
                if (expected == null || !arenaLevel.getBlockState(foot).is(expected))
                {
                    missing++;
                }
            }
            check(failures, missing == 0, room.id() + " 有 " + missing + " 道隔断没砌出来（或材质不对）");
            int sealed = sealedStandableCells(arenaLevel, origin, room);
            check(failures, sealed == 0, room.id() + " 的隔断在房里封出了 " + sealed
                    + " 格能站人却走不到的地方：怪卡在里面就打不到，波次永远清不完");
            int badPoints = 0;
            for (BlockPos point : built.spawnPoints())
            {
                if (!standable(arenaLevel, point.below()))
                {
                    badPoints++;
                }
            }
            check(failures, badPoints == 0, room.id() + " 有 " + badPoints + " 个站怪点被隔断或遮挡物占住");
            com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell,
                    origin.getZ() + room.depth() - cellOrigin.getZ(), built.region());
            check(failures, countRoomBlocks(arenaLevel, origin, room) == 0,
                    room.id() + " 拆场后仍有残留（隔断没进笔刷记的足迹）");
            cell++;
        }
        check(failures, withPartitions >= 3,
                "只有 " + withPartitions + " 间房配了 partitions，隔断判据覆盖面不足");
    }

    /** partitions 的五条校验都要有牙。 */
    private static void probePartitionRulesRejected(List<String> failures)
    {
        // 9x5x9、spawn_points=1 的小房：唯一的站怪点固定落在局部 (1,1)
        String onSpawnPoint = partitionRoom(1, 1, 1, 3, 1).resolve("trial_portal:probe_p_spawn");
        check(failures, onSpawnPoint != null, "隔断压住站怪点没被拦（怪会卡进墙里）");
        String diagonal = partitionRoom(3, 2, 6, 6, 1).resolve("trial_portal:probe_p_diag");
        check(failures, diagonal != null, "斜着的隔断没被拦（斜线铺出来是断格的）");
        String outside = partitionRoom(0, 1, 0, 7, 1).resolve("trial_portal:probe_p_outside");
        check(failures, outside != null, "越出室内的隔断没被拦（贴外墙写等于改外墙）");
        String tooTall = partitionRoom(6, 3, 6, 7, 99).resolve("trial_portal:probe_p_tall");
        check(failures, tooTall != null, "height 超过室内高没被拦");
        String onAnchor = bossPartitionRoom().resolve("trial_portal:probe_p_anchor");
        check(failures, onAnchor != null, "BOSS 房的隔断压住中心舞台没被拦（BOSS 会嵌在墙里）");
        String sane = partitionRoom(6, 3, 6, 7, 2).resolve("trial_portal:probe_p_sane");
        check(failures, sane == null, "合法隔断被误判：" + sane);
    }

    /** 一间 9x5x9 的探针房，里面只有一道竖向隔断 (x0,z0)-(x1,z1)。 */
    private static RoomDefinition partitionRoom(int x0, int z0, int x1, int z1, int height)
    {
        RoomDefinition room = new RoomDefinition();
        room.size = List.of(9, 5, 9);
        room.spawnPoints = 1;
                room.partitions.add(barrier(x0, z0, x1, z1, height));
        return room;
    }

    private static RoomDefinition.Partition barrier(int x0, int z0, int x1, int z1, int height)
    {
        RoomDefinition.Partition partition = new RoomDefinition.Partition();
        partition.name = "探针隔断";
        partition.from = List.of(x0, z0);
        partition.to = List.of(x1, z1);
        partition.height = height;
        partition.block = "minecraft:blackstone_wall";
        return partition;
    }

    /** BOSS 房探针：隔断横跨中心舞台 (9,9)。 */
    private static RoomDefinition bossPartitionRoom()
    {
        RoomDefinition room = new RoomDefinition();
        room.size = List.of(19, 8, 19);
        room.spawnPoints = 2;
                room.bossRoom = true;
        room.partitions.add(barrier(9, 7, 9, 11, 2));
        return room;
    }

    /**
     * 自家能拆的方块被拆掉时要真的掉出自己。战利品表走原版命名约定
     * {@code <namespace>:blocks/<path>}，文件漏了、条目物品名写错、或方块被写成 {@code noLootTable()}，
     * 原版都不报错——玩家那边只是"拆了门什么也没拿到"。所以要一路走到真掉落列表，不只看文件在不在。
     * <p>
     * 三个玩法门 + 通道那台都算：通道在野外拆得掉（硬度和玩法门同档），拆了拿不回来就等于通道丢了。
     */
    private static void probePortalBlocksDropThemselves(MinecraftServer server, List<String> failures)
    {
        ServerLevel overworld = server.overworld();
        int checked = 0;
        List<String> defects = new ArrayList<>();
        for (net.minecraft.world.level.block.Block block : net.minecraftforge.registries.ForgeRegistries.BLOCKS)
        {
            if (!(block instanceof TrialPortalBlock) && !(block instanceof com.trialportal.block.ArenaExitBlock))
            {
                continue;
            }
            checked++;
            ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
            ResourceLocation expected = ResourceLocation.fromNamespaceAndPath(key.getNamespace(),
                    "blocks/" + key.getPath());
            ResourceLocation written = block.getLootTable();
            if (!expected.equals(written))
            {
                defects.add(key + " 的战利品表指向 " + written + "，不是原版约定的 " + expected
                        + "（写错或 noLootTable() 都会让拆门白拆）");
                continue;
            }
            net.minecraft.world.level.storage.loot.LootTable table = server.getLootData().getElement(
                    new net.minecraft.world.level.storage.loot.LootDataId<>(
                            net.minecraft.world.level.storage.loot.LootDataType.TABLE, expected));
            if (table == null || table == net.minecraft.world.level.storage.loot.LootTable.EMPTY)
            {
                defects.add(key + " 缺战利品表（" + expected + " 没加载出来），拆掉就是什么都不掉");
                continue;
            }
            net.minecraft.world.item.Item wanted = block.asItem();
            if (wanted == Items.AIR)
            {
                defects.add(key + " 没有对应的物品，掉了也拿不到手");
                continue;
            }
            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(block.defaultBlockState(),
                    overworld, overworld.getSharedSpawnPos(), null);
            int matched = 0;
            for (ItemStack drop : drops)
            {
                if (drop.getItem() == wanted)
                {
                    matched += drop.getCount();
                }
            }
            if (matched != 1)
            {
                defects.add(key + " 拆掉后掉的是 " + drops + "，应有 1 个 " + wanted
                        + "（条目 name 写错时原版把它换成空气，游戏一声不响）");
            }
        }
        check(failures, checked >= 4,
                "注册表里至少该有 3 个玩法门 + 1 台通道方块，实际 " + checked + "——掉落判据根本没跑到东西");
        check(failures, defects.isEmpty(), String.join("；", defects));
    }

    /**
     * 开服清扫分两步（{@code sweepOrphanCells} → 下一轮 {@code tickPendingReleases}），它跑在玩家登录<b>之前</b>。
     * 回返点必须活着走完这两步：以前第二步顺手 {@code takeHome}，等真人上线时家已被吃掉，
     * {@code rescueStrandedPlayers} 只能把人丢去世界出生点。这里摆出"有平台 + 有回返点"的无主隔间，跑完两步再核。
     */
    private static void probeHomeSurvivesStartupSweep(MinecraftServer server, ServerLevel arena,
            List<String> failures)
    {
        TrialManager manager = TrialManager.get(server);
        java.util.UUID orphan = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(orphan, TrialMode.TRIAL, null);
        check(failures, cell >= 0, "试炼段分不到隔间编号，回返点判据根本没跑");
        if (cell < 0)
        {
            return;
        }
        int radius = com.trialportal.arena.ArenaService.radius();
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        com.trialportal.arena.ArenaService.buildPlatform(arena, origin, TrialMode.TRIAL);
        manager.recordHome(orphan, new TrialManager.HomePos(Level.OVERWORLD, 8.5D, 200.0D, 8.5D, 0.0F, 0.0F));
        // 前置：状态没摆出来，后面的判据就会在空数据上跑绿
        check(failures, countPlatformBlocks(arena, origin, radius) > 0,
                "前置失败：隔间 " + cell + " 的平台没建起来，清扫判据无从可核");
        check(failures, manager.hasHome(orphan), "前置失败：回返点没记进去，后面的「还在不在」是句空话");

        int swept = com.trialportal.arena.ArenaService.sweepOrphanCells(server);
        check(failures, swept >= 1, "开服清扫没把这个无主隔间算进去（swept=" + swept + "），第二步压根不会跑");
        com.trialportal.arena.ArenaService.tickPendingReleases(server);

        check(failures, manager.hasHome(orphan),
                "开服清扫第二步把回返点吃掉了：玩家此刻还没登录，等他上线只能被丢去世界出生点");
        check(failures, countPlatformBlocks(arena, origin, radius) == 0,
                "清扫之后隔间 " + cell + " 的平台方块还留在原地");
        check(failures, !manager.cellsSnapshot().containsValue(cell),
                "清扫之后隔间编号还没销账，下一次分配会绕过它");
    }

    /**
     * 关卡里写的 {@code count} 必须真的变成出怪配比。以前这条字段只有校验在读、游戏不读，
     * 写 4 和写 1 在场上一个样——"配了等于没配"。这里按「声明份数 == 队列槽位」对账，
     * 并要求同一只怪连着占满自己那几槽：轮转取队首时比例才成立，打乱了就变成均匀撒。
     */
    private static void probeChallengeSpawnPlan(List<String> failures)
    {
        int checked = 0;
        List<String> defects = new ArrayList<>();
        for (ChallengeDefinition challenge : ChallengeLibrary.ordered())
        {
            for (String key : ChallengeDefinition.DIFFICULTY_KEYS)
            {
                ChallengeDefinition.Difficulty difficulty = challenge.difficulties.get(key);
                if (difficulty == null)
                {
                    continue;
                }
                checked++;
                int declared = 0;
                for (TrialDefinition.MobEntry entry : difficulty.mobs)
                {
                    declared += Math.max(1, entry.count);
                }
                List<TrialDefinition.MobEntry> plan = difficulty.spawnPlan();
                if (plan.size() != declared)
                {
                    defects.add(challenge.id() + "/" + key + " 声明的份数是 " + declared
                            + "，展开出来的配比队列却有 " + plan.size() + " 槽");
                    continue;
                }
                int slot = 0;
                boolean misplaced = false;
                for (TrialDefinition.MobEntry entry : difficulty.mobs)
                {
                    for (int i = 0; i < Math.max(1, entry.count); i++)
                    {
                        if (plan.get(slot++) != entry)
                        {
                            misplaced = true;
                        }
                    }
                }
                if (misplaced)
                {
                    defects.add(challenge.id() + "/" + key + " 的队列顺序与 mobs[] 对不上，比例会被打乱");
                }
            }
        }
        check(failures, checked >= 6, "至少该核到 6 档闯关配比，实际 " + checked + "——这条判据没跑到数据");
        check(failures, defects.isEmpty(), String.join("；", defects));
    }

    /**
     * 数据里写的怪名必须真在实体注册表里。这条只能开在运行期：离线进程看不到注册表。
     * 为什么值得单独立一条——找不到时 {@code TrialManager} 只是 {@code LOGGER.warn} 一句就跳过，
     * 那一波/那一档就永远刷不出东西：玩家站在空场里打到超时，而游戏一声不响。
     * 整合包自己塞的 datapack 内容从没被我们的离线门扫过，全靠这条兜底。
     */
    private static void probeMobIdsResolve(MinecraftServer server, List<String> failures)
    {
        ServerLevel level = server.overworld();
        Set<String> checked = new java.util.LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        List<String> notSpawnable = new ArrayList<>();
        for (TrialDefinition definition : TrialLibrary.snapshot().values())
        {
            String where = definition.id();
            for (TrialDefinition.Wave wave : definition.waves)
            {
                collectMobIds(level, wave.mobs, where, checked, missing, notSpawnable);
            }
            if (definition.scaling != null)
            {
                for (TrialDefinition.Tier tier : definition.scaling.tiers)
                {
                    for (String entity : tier.mobs)
                    {
                        noteMobId(level, entity, where, checked, missing, notSpawnable);
                    }
                }
                for (TrialDefinition.FloorOverride override : definition.scaling.floors)
                {
                    collectMobIds(level, override.mobs, where, checked, missing, notSpawnable);
                }
            }
            if (definition.boss != null)
            {
                noteMobId(level, definition.boss.entity, where, checked, missing, notSpawnable);
            }
        }
        for (ChallengeDefinition challenge : ChallengeLibrary.ordered())
        {
            for (ChallengeDefinition.Difficulty difficulty : challenge.difficulties.values())
            {
                collectMobIds(level, difficulty.mobs, challenge.id(), checked, missing, notSpawnable);
            }
        }
        check(failures, checked.size() >= 8,
                "至少该核到 8 个不重复的怪物 id，实际 " + checked.size() + "——这条判据没跑到内容");
        check(failures, missing.isEmpty(),
                "这些 entity 不在实体注册表里，生成点只会 warn 一句然后跳过（那场打不完）：" + missing);
        check(failures, notSpawnable.isEmpty(),
                "这些 entity 注册表里有，但不是能刷出来的生物（同样会被跳过）：" + notSpawnable);
    }

    private static void collectMobIds(ServerLevel level, List<TrialDefinition.MobEntry> entries, String where,
            Set<String> checked, List<String> missing, List<String> notSpawnable)
    {
        for (TrialDefinition.MobEntry entry : entries)
        {
            if (entry != null)
            {
                noteMobId(level, entry.entity, where, checked, missing, notSpawnable);
            }
        }
    }

    private static void noteMobId(ServerLevel level, String raw, String where, Set<String> checked,
            List<String> missing, List<String> notSpawnable)
    {
        if (raw == null || raw.isBlank() || !checked.add(raw))
        {
            return; // 空条目由离线门的 resolve() 负责，这里只核"存不存在"
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        // 这里不能用 getValue()：1.20.1 的 Forge 注册表对不存在的 id 会回落到默认条目而不是 null
        // （变异抽查实测：把 zombie 写成 zombia，getValue 照样返回一个非空值，判据静默空转）。
        if (id == null || !net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id))
        {
            missing.add(where + " → " + raw + (id == null ? "（写法不合法）" : ""));
            return;
        }
        net.minecraft.world.entity.EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getValue(id);
        try
        {
            if (!(type.create(level) instanceof net.minecraft.world.entity.Mob))
            {
                notSpawnable.add(where + " → " + raw + "（" + type.getCategory().getName() + "）");
            }
        }
        catch (Throwable throwable)
        {
            notSpawnable.add(where + " → " + raw + " 构造失败 " + throwable.getClass().getName());
        }
    }

    /**
     * 守塔内容自核：波次非空、塔血为正、结界半径既在合法区间又不越出隔间能给的地，
     * 外加各玩法的编号段两两不重叠（重叠就等于两个人共占一块地，拆台会拆到别人脸上）。
     */
    private static void probeDefenseDefinitions(MinecraftServer server, List<String> failures)
    {
        List<DefenseDefinition> list = DefenseLibrary.ordered();
        check(failures, !list.isEmpty(), "defenses/ 一份守塔定义都没有，守塔模式开不出局");
        List<String> defects = new ArrayList<>();
        int entries = 0;
        int limit = com.trialportal.arena.ArenaService.spacing() / 2 - 1;
        for (DefenseDefinition defense : list)
        {
            if (defense.waves.isEmpty())
            {
                defects.add(defense.id() + " 没有波次");
            }
            if (defense.towerHealth <= 0.0D)
            {
                defects.add(defense.id() + " 塔血 " + defense.towerHealth + "（开局即归零等于直接判负）");
            }
            if (defense.wardRadius < DefenseDefinition.MIN_WARD_RADIUS
                    || defense.wardRadius > DefenseDefinition.MAX_WARD_RADIUS)
            {
                defects.add(defense.id() + " 结界半径 " + defense.wardRadius + " 超出 "
                        + DefenseDefinition.MIN_WARD_RADIUS + '~' + DefenseDefinition.MAX_WARD_RADIUS);
            }
            else if (defense.wardRadius > limit)
            {
                defects.add(defense.id() + " 结界半径 " + defense.wardRadius + " 会啃到相邻隔间（间隔 "
                        + com.trialportal.arena.ArenaService.spacing() + "，最多 " + limit + "）");
            }
            for (TrialDefinition.Wave wave : defense.waves)
            {
                if (wave == null || wave.mobs.isEmpty())
                {
                    defects.add(defense.id() + " 有一波是空的");
                    continue;
                }
                entries += wave.mobs.size();
            }
        }
        check(failures, defects.isEmpty(), String.join("；", defects));
        check(failures, entries >= 4, "守塔至少该有 4 条出怪条目，实际 " + entries + "——判据没跑到内容");

        TrialMode[] modes = TrialMode.values();
        for (int i = 0; i < modes.length; i++)
        {
            for (int j = i + 1; j < modes.length; j++)
            {
                int gap = Math.abs(modes[i].cellBase() - modes[j].cellBase());
                check(failures, gap >= TrialManager.CELLS_PER_MODE,
                        modes[i] + " 与 " + modes[j] + " 的隔间编号段重叠（间距 " + gap + " < "
                                + TrialManager.CELLS_PER_MODE + "）");
            }
        }
        // 守塔段真能分到地：编号必须落在本段内
        TrialManager manager = TrialManager.get(server);
        java.util.UUID tester = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:defense".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(tester, TrialMode.DEFENSE, null);
        check(failures, cell >= TrialMode.DEFENSE.cellBase()
                        && cell < TrialMode.DEFENSE.cellBase() + TrialManager.CELLS_PER_MODE,
                "守塔段分到的编号 " + cell + " 不在 " + TrialMode.DEFENSE.cellBase() + "~"
                        + (TrialMode.DEFENSE.cellBase() + TrialManager.CELLS_PER_MODE - 1) + " 之内");
        manager.freeCell(tester);
    }

    /**
     * 守塔的塔：注册齐不齐、大厅那座是不是入口、结界台面铺没铺满、四面有没有门、血量池能不能存能读、
     * 拆台后方块与强制加载区块还得还回去。（"不许有合成配方"记在 {@link #probePassageRecipes} 那张账里，
     * 这儿不再扫第二遍配方。）
     */
    private static void probeWardPylon(MinecraftServer server, ServerLevel arena, List<String> failures)
    {
        net.minecraft.world.level.block.Block block = com.trialportal.registry.ModBlocks.WARD_PYLON.get();
        check(failures, net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(
                        ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, "ward_pylon")),
                "ward_pylon 方块没注册进注册表");
        check(failures, block.asItem() != Items.AIR, "ward_pylon 没有物品，创造栏拿不到它（它按裁定不进生存配方）");
        // 手里拿着可放置方块时，右键必须仍然落到功能方块身上（真机：拿着别的 mod 的战利品方块点不动塔）
        check(failures, TrialEvents.keepsBlockUse(block.defaultBlockState())
                        && TrialEvents.keepsBlockUse(com.trialportal.registry.ModBlocks.CHALLENGE_PORTAL.get()
                        .defaultBlockState())
                        && TrialEvents.keepsBlockUse(com.trialportal.registry.ModBlocks.ARENA_EXIT.get()
                        .defaultBlockState()),
                "自家功能终端没被登记成「右键留给方块自己」——手里拿着方块时点门/点塔会一声不响");
        check(failures, !TrialEvents.keepsBlockUse(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                        && !TrialEvents.keepsBlockUse(
                        net.minecraft.world.level.block.Blocks.SHROOMLIGHT.defaultBlockState()),
                "keepsBlockUse 见方块就放行，等于把全场所有方块上的「手里物品一用」都取消了，不是这条修复的本意");

        BlockPos lobby = com.trialportal.arena.ArenaService.cellOrigin(com.trialportal.arena.ArenaService.LOBBY_CELL);
        com.trialportal.arena.ArenaService.buildPlatform(arena, lobby, null);
        int hallRadius = com.trialportal.arena.ArenaService.radius();
        BlockPos entry = lobby.offset(0, 1, hallRadius - 1);
        check(failures, arena.getBlockState(entry).getBlock() == block,
                "大厅南沿正中应有守塔入口（北沿是三座拱门），实际是 "
                        + arena.getBlockState(entry).getBlock() + " @ " + entry.toShortString());
        BlockEntity entryEntity = arena.getBlockEntity(entry);
        check(failures, entryEntity instanceof com.trialportal.block.entity.WardPylonBlockEntity pylon
                        && pylon.isEntry(),
                "大厅那座塔必须是「入口」（没有主人）：右键它才该开局，实际是 "
                        + (entryEntity == null ? "没有方块实体" : entryEntity.getClass().getSimpleName()));

        TrialManager manager = TrialManager.get(server);
        java.util.UUID owner = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:ward".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(owner, TrialMode.DEFENSE, null);
        check(failures, cell >= 0, "守塔段分不到编号，结界判据根本没跑");
        if (cell < 0)
        {
            return;
        }
        int ward = 20;
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        com.trialportal.arena.ArenaService.buildPlatform(arena, origin, TrialMode.DEFENSE, ward);
        net.minecraft.world.phys.AABB built = new net.minecraft.world.phys.AABB(
                origin.getX() - ward - 1, origin.getY(), origin.getZ() - ward - 1,
                origin.getX() + ward + 2, origin.getY() + com.trialportal.arena.ArenaService.fenceHeight() + 2,
                origin.getZ() + ward + 2);
        manager.recordBuilt(owner, 0, built);
        int floor = countPlatformBlocks(arena, origin, ward);
        check(failures, floor >= (2 * ward - 1) * (2 * ward - 1),
                "结界台面没按 ward 半径铺满：数到 " + floor + " 块（半径 " + ward + "）");
        int blocked = 0;
        for (int[] gate : new int[][]{{ward, 0}, {-ward, 0}, {0, ward}, {0, -ward}})
        {
            if (!arena.getBlockState(origin.offset(gate[0], 1, gate[1])).isAir())
            {
                blocked++;
            }
        }
        check(failures, blocked == 0, "守塔结界四面都要留门（怪要从外面走进来），有 " + blocked + " 面被围栏堵死");
        check(failures, !arena.getBlockState(origin.offset(ward, 1, ward)).isAir(),
                "结界角上不该开门（那是围栏），那里是空的——怪能直接跳进来");

        // 塔由 buildPlatform 自己补在台心（上面那次建台），这里只取回来核血量池
        BlockPos towerPos = origin.offset(0, 1, 0);
        check(failures, arena.getBlockState(towerPos).getBlock() == block,
                "守塔隔间的台心应有塔，实际是 " + arena.getBlockState(towerPos).getBlock());
        if (!(arena.getBlockEntity(towerPos) instanceof com.trialportal.block.entity.WardPylonBlockEntity tower))
        {
            check(failures, false, "放了 ward_pylon 却拿不到方块实体，血量池无从可核");
            return;
        }
        tower.configure(120.0D, owner, "trial_portal:first_ward");
        check(failures, tower.towerHealth() == 120.0D && !tower.destroyed(),
                "开局应满血未破，实际 " + tower.towerHealth());
        CompoundTag saved = tower.saveWithoutMetadata();
        com.trialportal.block.entity.WardPylonBlockEntity reloaded =
                new com.trialportal.block.entity.WardPylonBlockEntity(towerPos, block.defaultBlockState());
        reloaded.load(saved);
        check(failures, reloaded.towerMax() == 120.0D && reloaded.towerHealth() == 120.0D
                        && owner.equals(reloaded.owner()) && "trial_portal:first_ward".equals(reloaded.defenseId()),
                "血量池 NBT 往返不一致（max=" + reloaded.towerMax() + " hp=" + reloaded.towerHealth()
                        + " owner=" + reloaded.owner() + " id=" + reloaded.defenseId() + "）");
        reloaded.damage(50.0D);
        check(failures, Math.abs(reloaded.towerHealth() - 70.0D) < 1.0E-6D && !reloaded.destroyed(),
                "扣 50 应剩 70 且未破，实际 " + reloaded.towerHealth());
        check(failures, reloaded.damage(500.0D) && reloaded.destroyed() && reloaded.towerHealth() == 0.0D,
                "打到归零必须报「这次破了」（判负靠它），实际 hp=" + reloaded.towerHealth());

        com.trialportal.arena.ArenaService.releaseCellAt(arena, cell, 0, built);
        check(failures, countPlatformBlocks(arena, origin, ward) == 0,
                "拆完结界台面还剩 " + countPlatformBlocks(arena, origin, ward) + " 块");
        check(failures, !hasForcedChunkIn(arena, origin.offset(-ward - 1, 0, -ward - 1),
                        origin.offset(ward + 1, 0, ward + 1)),
                "拆完结界要把建房时强制加载的区块还回去");
        manager.freeCell(owner);
    }

    /**
     * 守塔的波次阶梯：每一波都取得到内容、定义级倍率只乘一次、波次表走到底必须取不到下一波
     * （通关就靠这条 null），间歇的三级取值与 {@code boss_affix_pool} 真被读到也在这里核。
     */
    private static void probeDefenseWaveLadder(List<String> failures)
    {
        List<DefenseDefinition> list = DefenseLibrary.ordered();
        check(failures, !list.isEmpty(), "守塔波次阶梯没有内容可核：defenses/ 是空的");
        for (DefenseDefinition defense : list)
        {
            int waves = defense.finiteWaveCount();
            check(failures, waves == defense.waves.size(),
                    defense.id() + " 的 finiteWaveCount 报 " + waves + "，波次表却有 " + defense.waves.size() + " 条");
            double definitionHp = defense.strength == null ? 1.0D : defense.strength.healthMultiplier;
            int expanded = 0;
            for (int index = 0; index < waves; index++)
            {
                TrialDefinition.Wave wave = defense.waves.get(index);
                TrialDefinition.WaveContent content = defense.waveFor(index);
                if (content == null)
                {
                    check(failures, false, defense.id() + " 第 " + (index + 1) + " 波取不到内容——状态机把它读成"
                            + "「没有更多波」，这场会在第 " + (index + 1) + " 波直接判通关");
                    break;
                }
                check(failures, !content.mobs.isEmpty(), defense.id() + " 第 " + (index + 1) + " 波展开后没有怪");
                check(failures, content.title != null && !content.title.isBlank(),
                        defense.id() + " 第 " + (index + 1) + " 波没有标题，播报会显示 null");
                int count = 0;
                for (TrialDefinition.MobEntry entry : content.mobs)
                {
                    count += Math.max(1, entry.count);
                }
                expanded += count;
                double waveHp = wave.strength == null ? 1.0D : wave.strength.healthMultiplier;
                double combinedHp = content.strength == null ? 1.0D : content.strength.healthMultiplier;
                check(failures, Math.abs(combinedHp - definitionHp * waveHp) < 1.0E-9D,
                        defense.id() + " 第 " + (index + 1) + " 波的血量倍率应是定义级 " + definitionHp
                                + " × 波次级 " + waveHp + " = " + (definitionHp * waveHp) + "，实际 "
                                + combinedHp + "（倍率乘了不止一次或没乘上）");
                boolean hasBoss = content.mobs.stream().anyMatch(entry -> entry.boss);
                if (!defense.bossAffixPool.isEmpty())
                {
                    check(failures, !hasBoss || content.affixPool != null,
                            defense.id() + " 写了 boss_affix_pool，但带 BOSS 的那一波取不到池子（会退回全量词条）");
                }
            }
            check(failures, expanded > 0, defense.id() + " 整份波次表一只怪都没展开出来");
            check(failures, defense.waveFor(waves) == null,
                    defense.id() + " 的波次表只有 " + waves + " 条，第 " + (waves + 1) + " 波却还取得到内容——"
                            + "通关永远不会触发，玩家得在场外一直等");
            check(failures, defense.waveFor(-1) == null, defense.id() + " 的 waveFor(-1) 没返回 null");
            for (int index = 0; index <= waves; index++)
            {
                int ticks = defense.intermissionTicksFor(index);
                TrialDefinition.Wave wave = index < waves ? defense.waves.get(index) : null;
                int expected = wave != null && wave.intermissionTicks >= 0 ? wave.intermissionTicks
                        : defense.intermissionTicks;
                check(failures, ticks == expected, defense.id() + " 第 " + (index + 1) + " 波后的间歇应是 "
                        + expected + "，实际 " + ticks + "（波里写的没被读，或越界没回落到定义级）");
                check(failures, ticks >= 20, defense.id() + " 第 " + (index + 1) + " 波后的间歇只有 " + ticks
                        + " tick，不到一秒就被下一波顶上去");
            }
        }
        // 靶子由门自种：自带内容的定义级倍率写的是 1.0（恒等），拿它核不出「定义级被丢掉」与「被乘两遍」，
        // 所以这里现场造一份两端都不为 1 的定义来折。
        DefenseDefinition chain = new DefenseDefinition();
        chain.wardRadius = DefenseDefinition.MIN_WARD_RADIUS;
        chain.strength = new MobStrength();
        chain.strength.healthMultiplier = 2.0D;
        chain.strength.armorBonus = 1.0D;
        TrialDefinition.Wave layer = new TrialDefinition.Wave();
        layer.strength = new MobStrength();
        layer.strength.healthMultiplier = 1.5D;
        layer.strength.armorBonus = 0.5D;
        TrialDefinition.MobEntry mob = new TrialDefinition.MobEntry();
        mob.entity = "minecraft:zombie";
        layer.mobs.add(mob);
        chain.waves.add(layer);
        TrialDefinition.WaveContent folded = chain.waveFor(0);
        check(failures, folded != null && folded.strength != null
                        && Math.abs(folded.strength.healthMultiplier - 3.0D) < 1.0E-9D
                        && Math.abs(folded.strength.armorBonus - 1.5D) < 1.0E-9D,
                "强度链折错了：定义级 2.0 与波次级 1.5 应乘成 3.0、加值 1.0+0.5 应折成 1.5，实际 "
                        + (folded == null || folded.strength == null ? "整条强度都没了"
                        : folded.strength.healthMultiplier + " / " + folded.strength.armorBonus));
    }

    /**
     * 守塔状态机的四条分支：塔破、还在打、本波清空、最后一波清空。
     * 判负必须排在判赢之前——最后一只怪死在塔归零之后那一同一 tick，这场算输。
     */
    private static void probeDefenseSteps(List<String> failures)
    {
        DefenseDefinition defense = DefenseLibrary.ordered().isEmpty() ? null : DefenseLibrary.ordered().get(0);
        if (defense == null)
        {
            failures.add("守塔分支裁决没跑：defenses/ 是空的");
            return;
        }
        int last = defense.finiteWaveCount() - 1;

        TrialSession fighting = defenseSession();
        fighting.waveIndex = 0;
        fighting.aliveMobs.add(java.util.UUID.randomUUID());
        check(failures, TrialManager.nextDefenseStep(fighting, defense, false) == TrialManager.DefenseStep.FIGHTING,
                "场上还有怪时必须算「交战中」，否则下一波会叠着刷");
        check(failures, TrialManager.nextDefenseStep(fighting, defense, true) == TrialManager.DefenseStep.LOST_TOWER,
                "塔血归零时哪怕怪还在也必须判负");

        TrialSession cleared = defenseSession();
        cleared.waveIndex = 0;
        check(failures, TrialManager.nextDefenseStep(cleared, defense, false) == TrialManager.DefenseStep.WAVE_CLEARED,
                "第 1 波清空且还有下一波时该进间歇，实际不是");

        cleared.phase = TrialSession.Phase.INTERMISSION;
        cleared.intermissionTicksLeft = 100;
        check(failures, TrialManager.nextDefenseStep(cleared, defense, false) == TrialManager.DefenseStep.COUNTDOWN,
                "间歇中（场上没怪）不该被算成交战或清空");
        check(failures, TrialManager.nextDefenseStep(cleared, defense, true) == TrialManager.DefenseStep.LOST_TOWER,
                "间歇里塔被人拆了也必须立刻判负，不能等倒计时走完");

        TrialSession won = defenseSession();
        won.waveIndex = last;
        check(failures, TrialManager.nextDefenseStep(won, defense, false) == TrialManager.DefenseStep.WON,
                "最后一波清空且塔还活着就是守住，实际不是");
        check(failures, TrialManager.nextDefenseStep(won, defense, true) == TrialManager.DefenseStep.LOST_TOWER,
                "同一 tick 里「最后一波清空」与「塔归零」并存时必须算输");

        // 只有一波的守塔：清空即通关，不能永远等在「还有下一波」上
        DefenseDefinition single = new DefenseDefinition();
        single.wardRadius = DefenseDefinition.MIN_WARD_RADIUS;
        TrialDefinition.Wave only = new TrialDefinition.Wave();
        TrialDefinition.MobEntry entry = new TrialDefinition.MobEntry();
        entry.entity = "minecraft:zombie";
        entry.count = 1;
        only.mobs.add(entry);
        single.waves.add(only);
        TrialSession singleWave = defenseSession();
        singleWave.waveIndex = 0;
        check(failures, single.finiteWaveCount() == 1 && single.waveFor(0) != null && single.waveFor(1) == null,
                "单波守塔定义应能取到第 1 波且第 2 波为 null");
        check(failures, TrialManager.nextDefenseStep(singleWave, single, false) == TrialManager.DefenseStep.WON,
                "单波定义清空后必须直接判通关，否则这一场永远结束不了");
        check(failures, single.intermissionTicksFor(0) == single.intermissionTicks
                        && single.intermissionTicksFor(9) == single.intermissionTicks,
                "没写间歇的波次要回落到定义级默认（越界也一样），实际不是");

        // 会话的玩法与播报名：三种内容目录各查一次。真机上守塔曾被打印成 (challenge)、
        // 点别的门时报「你当前那一场是「null」」——两条都因为"trials/ 查不到就当它是副本"。
        TrialSession labeled = defenseSession();
        labeled.mode = TrialMode.DEFENSE;
        labeled.defenseId = defense.id();
        check(failures, labeled.modeOf(null) == TrialMode.DEFENSE,
                "守塔会话的玩法应是自己钉的 DEFENSE（拿 definitionId 去 trials/ 反查会错成 "
                        + labeled.modeOf(null) + "）");
        check(failures, defense.displayNameOrDefault().equals(labeled.displayName()),
                "守塔会话的播报名应是「" + defense.displayNameOrDefault() + "」，实际拿到「"
                        + labeled.displayName() + "」");
        TrialSession legacy = new TrialSession(
                java.util.UUID.nameUUIDFromBytes("trial_portal:selftest:legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "selftest", "trial_portal:gone", net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO);
        check(failures, legacy.mode == null && legacy.modeOf(null) == TrialMode.CHALLENGE,
                "老会话（没钉玩法、定义也查不到）应回退成 CHALLENGE，与改动前一致");
        check(failures, "trial_portal:gone".equals(legacy.displayName()),
                "三份目录都查不到时应老实回落到 id，实际「" + legacy.displayName() + "」");
    }

    private static TrialSession defenseSession()
    {
        return new TrialSession(java.util.UUID.nameUUIDFromBytes("trial_portal:selftest:defense-session".
                getBytes(java.nio.charset.StandardCharsets.UTF_8)), "selftest", "trial_portal:probe",
                net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO);
    }

    /**
     * 结界外缘落点：真建一台结界再扫 80 个点。
     * 要的性质是「踩在台面上、落在围栏里、离塔有段距离」——越过围栏就把怪丢进未加载的场外，
     * 那一波就永远清不完；全落在塔脚下则怪一出生就叠进方块里。
     */
    private static void probeDefenseEdgeSpawn(MinecraftServer server, ServerLevel arena, List<String> failures)
    {
        TrialManager manager = TrialManager.get(server);
        java.util.UUID owner = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:edge".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(owner, TrialMode.DEFENSE, null);
        if (cell < 0)
        {
            check(failures, false, "守塔段分不到编号，结界落点判据根本没跑");
            return;
        }
        int ward = 14;
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        com.trialportal.arena.ArenaService.buildPlatform(arena, origin, TrialMode.DEFENSE, ward);
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        int outside = 0;
        int notStandable = 0;
        int offDeck = 0;
        Vec3 center = Vec3.atCenterOf(origin);
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(20261008L);
        for (int sample = 0; sample < 80; sample++)
        {
            Vec3 point = TrialManager.wardEdgePosition(arena, origin, ward, random);
            BlockPos standing = BlockPos.containing(point);
            distinct.add(standing.toShortString());
            if (!arena.getBlockState(standing.below()).isRedstoneConductor(arena, standing.below())
                    || !arena.getBlockState(standing).isAir() || !arena.getBlockState(standing.above()).isAir())
            {
                notStandable++;
            }
            if (standing.getY() != origin.getY() + 1)
            {
                offDeck++;
            }
            double distance = Math.sqrt(point.distanceToSqr(center));
            if (distance < ward * 0.45D || distance > ward + 0.6D)
            {
                outside++;
            }
        }
        check(failures, distinct.size() >= 20,
                "结界外缘的 80 次取样只落在 " + distinct.size() + " 个点上——多半全部退回了兜底位");
        check(failures, notStandable == 0, notStandable + " 个外缘落点脚下不实心或头顶被挡，怪会悬空或卡进方块");
        check(failures, offDeck == 0, offDeck + " 个落点不在台面那一层（应为 origin.y+1），怪会掉到台下去");
        check(failures, outside == 0,
                "有落点越过围栏或挤在塔脚下（合法区间 " + (ward * 0.45D) + "~" + (ward + 0.6) + "）：场外的区块没被强制加载，"
                        + "怪卡在未加载区里这一波就清不完");
        com.trialportal.arena.ArenaService.releaseCellAt(arena, cell, 0, new net.minecraft.world.phys.AABB(
                origin.getX() - ward - 1, origin.getY(), origin.getZ() - ward - 1,
                origin.getX() + ward + 2, origin.getY() + com.trialportal.arena.ArenaService.fenceHeight() + 2,
                origin.getZ() + ward + 2));
        manager.freeCell(owner);
    }

    /**
     * 创造配置口（{@code /tei defense}）的唯一写入口：改坏必须回滚、写不存在的怪必须当场拒、
     * 改的必须是这一场自己的副本，还有台面宽度要跟着这一场走。
     */
    private static void probeDefenseEdit(MinecraftServer server, List<String> failures)
    {
        List<DefenseDefinition> loaded = DefenseLibrary.ordered();
        if (loaded.isEmpty())
        {
            check(failures, false, "配置口没有内容可核：defenses/ 是空的");
            return;
        }
        DefenseDefinition library = loaded.get(0);
        double originalHealth = library.towerHealth;
        int originalWaves = library.finiteWaveCount();

        DefenseDefinition live = library.copy();
        check(failures, live.finiteWaveCount() == originalWaves && live.wardRadius == library.wardRadius
                        && Math.abs(live.towerHealth - originalHealth) < 1.0E-9D
                        && Math.abs(live.towerFocusShare - library.towerFocusShare) < 1.0E-9D,
                "copy() 没原样带出内容，开局拿到的就是半份定义");
        check(failures, DefenseEdit.setKnob(live, "tower_health", originalHealth + 600.0D) == null
                        && Math.abs(live.towerHealth - (originalHealth + 600.0D)) < 1.0E-9D,
                "改本场的塔血没改成（配置口等于摆设）");
        check(failures, Math.abs(library.towerHealth - originalHealth) < 1.0E-9D,
                "改这一场时把库里那份也改了：同一次加载里别人开局会拿到被调过的塔血");
        live.waves.get(0).mobs.get(0).count = 31;
        check(failures, library.waves.get(0).mobs.get(0).count != 31,
                "copy() 是浅拷贝——改一只怪的只数就写穿了库里的条目");

        DefenseDefinition rollback = library.copy();
        double shareBefore = rollback.towerFocusShare;
        String shareError = DefenseEdit.setKnob(rollback, "focus_share", 0.0D);
        check(failures, shareError != null, "focus_share=0（没有怪去拆塔）从配置口居然能写进去");
        check(failures, Math.abs(rollback.towerFocusShare - shareBefore) < 1.0E-9D,
                "被拒的写法留下了半个改动（拆塔怪比例现在是 " + rollback.towerFocusShare + "）——回滚没做");
        check(failures, DefenseEdit.setKnob(rollback, "no_such_knob", 1.0D) != null,
                "不认识的旋钮被静默接受了，打错字的人只会看到「什么都没发生」");

        DefenseDefinition adder = library.copy();
        String badId = DefenseEdit.addMob(server.overworld(), adder, 0, "minecraft:zombia", 1, false);
        check(failures, badId != null && badId.contains("注册表"),
                "写错怪物 id 居然通过了配置口（真机上它只会在生成点 warn 一句然后跳过）：" + badId);
        check(failures, adder.finiteWaveCount() == originalWaves
                        && adder.waves.get(0).mobs.size() == library.waves.get(0).mobs.size(),
                "被拒的条目还留在波次里");
        check(failures, DefenseEdit.addMob(server.overworld(), adder, originalWaves, "minecraft:zombie", 2, true) == null
                        && adder.finiteWaveCount() == originalWaves + 1,
                "下标=现有波数时应能追加新的一波（这是「给这场加一波」的唯一入口）");
        TrialDefinition.MobEntry appended = adder.waves.get(originalWaves).mobs.get(0);
        check(failures, appended.count == 2 && appended.boss,
                "追加那一波的只数/头目标志没落进去（" + appended.count + " / " + appended.boss + "）");
        check(failures, DefenseEdit.addMob(server.overworld(), adder, 99, "minecraft:zombie", 1, false) != null,
                "波次下标越界没拦住");
        check(failures, DefenseEdit.removeWave(adder, originalWaves, originalWaves) != null,
                "正在打的那一波居然能删（删完这一波就没有「下一波」可进了）");
        check(failures, DefenseEdit.removeWave(adder, 0, originalWaves) == null
                        && adder.finiteWaveCount() == originalWaves,
                "删掉不是正在打的那一波应能成");
        DefenseDefinition lastOnly = library.copy();
        lastOnly.waves.clear();
        TrialDefinition.Wave only = new TrialDefinition.Wave();
        TrialDefinition.MobEntry one = new TrialDefinition.MobEntry();
        one.entity = "minecraft:zombie";
        only.mobs.add(one);
        lastOnly.waves.add(only);
        check(failures, DefenseEdit.removeWave(lastOnly, 0, -1) != null,
                "只剩一波时还能删——删完 finiteWaveCount 就是 0，开局第一波都取不到");

        TrialSession ward = defenseSession();
        ward.mode = TrialMode.DEFENSE;
        ward.liveDefense = adder;
        int expect = Math.max(com.trialportal.arena.ArenaService.radius(), adder.wardRadius);
        check(failures, com.trialportal.arena.ArenaService.platformRadiusFor(ward) == expect,
                "守塔会话的台面宽度没跟着结界半径（应是 " + expect + "，实际 "
                        + com.trialportal.arena.ArenaService.platformRadiusFor(ward)
                        + "）——回大厅再回来会把结界削掉一圈，人站在被削掉的那半张台上就是站在虚空里");
        check(failures, com.trialportal.arena.ArenaService.platformRadiusFor(null)
                        == com.trialportal.arena.ArenaService.radius(),
                "没有会话时该用全局默认宽度（大厅就是默认宽度）");
        TrialSession dungeon = defenseSession();
        dungeon.mode = TrialMode.CHALLENGE;
        dungeon.liveDefense = adder;
        check(failures, com.trialportal.arena.ArenaService.platformRadiusFor(dungeon)
                        == com.trialportal.arena.ArenaService.radius(),
                "副本会话不该因为身上带着一份守塔内容就被加宽台面");
    }

    /**
     * 拆塔那只怪：分派比例与边界（纯判据）+ goal 真挂上、没走到不扣血、扣血按间隔、
     * 塔归零或怪出了结界就不再啃。
     * <p>
     * 这一族的"怪真的在动"要靠实体 tick，无头门看不见；所以把**能判的性质都搬到不碰 tick 的地方**：
     * 分派交给纯的 {@code DefenseFocus}，扣血由门自己驱动 {@code goal.tick()} 若干次后对账血量池的差值。
     */
    private static void probeTowerAssault(MinecraftServer server, ServerLevel arena, List<String> failures)
    {
        check(failures, DefenseFocus.forRoll(0.0D, 0.5D) == DefenseFocus.TOWER
                        && DefenseFocus.forRoll(0.49D, 0.5D) == DefenseFocus.TOWER
                        && DefenseFocus.forRoll(0.5D, 0.5D) == DefenseFocus.PLAYER
                        && DefenseFocus.forRoll(0.99D, 0.5D) == DefenseFocus.PLAYER,
                "分派边界不对：按 0.5 分派时 0.49 该去拆塔、0.5 该打人");
        check(failures, DefenseFocus.forRoll(-0.1D, 0.5D) == DefenseFocus.PLAYER
                        && DefenseFocus.forRoll(1.5D, 0.5D) == DefenseFocus.PLAYER,
                "越界的 roll 必须退回打人那支，不能猜它是 0 就全派去拆塔");
        net.minecraft.util.RandomSource rollStream = net.minecraft.util.RandomSource.create(777L);
        int toTower = 0;
        for (int i = 0; i < 400; i++)
        {
            if (DefenseFocus.forRoll(rollStream.nextDouble(), 0.5D) == DefenseFocus.TOWER)
            {
                toTower++;
            }
        }
        check(failures, toTower >= 160 && toTower <= 240,
                "400 次按 0.5 的分派只给了 " + toTower + " 只去拆塔——比例或随机流不对，"
                        + "「怪有两个目标」就成了一句空话");
        // 端点必须被 resolve 拦住：share=0 意味着没有怪去拆塔（那条判负分支永远走不到），
        // share=1 意味着玩家站在原地就赢。自种一份坏数据，看闸门咬不咬。
        DefenseDefinition degenerate = new DefenseDefinition();
        degenerate.wardRadius = DefenseDefinition.MIN_WARD_RADIUS;
        TrialDefinition.Wave seedWave = new TrialDefinition.Wave();
        TrialDefinition.MobEntry seedMob = new TrialDefinition.MobEntry();
        seedMob.entity = "minecraft:zombie";
        seedWave.mobs.add(seedMob);
        degenerate.waves.add(seedWave);
        degenerate.towerFocusShare = 0.0D;
        check(failures, degenerate.resolve("probe:zero_share") != null,
                "tower_focus_share=0（没有怪去拆塔）居然通过了校验");
        degenerate.towerFocusShare = 1.0D;
        check(failures, degenerate.resolve("probe:one_share") != null,
                "tower_focus_share=1（没有怪打玩家）居然通过了校验");
        degenerate.towerFocusShare = 0.5D;
        degenerate.towerHitIntervalTicks = 1;
        check(failures, degenerate.resolve("probe:fast_hit") != null,
                "tower_hit_interval_ticks=1（每 tick 扣一次）居然通过了校验");
        degenerate.towerHitIntervalTicks = 20;
        degenerate.towerDamagePerHit = degenerate.towerHealth * 2.0D;
        check(failures, degenerate.resolve("probe:one_shot") != null,
                "一口伤害比整塔血还大（塔瞬间没血）居然通过了校验");
        degenerate.towerDamagePerHit = 2.0D;
        check(failures, degenerate.resolve("probe:sane") == null,
                "改回正常值之后这份定义应能通过，实际报：" + degenerate.resolve("probe:sane"));

        TrialManager manager = TrialManager.get(server);
        java.util.UUID owner = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:assault".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(owner, TrialMode.DEFENSE, null);
        if (cell < 0)
        {
            check(failures, false, "守塔段分不到编号，拆塔判据根本没跑");
            return;
        }
        int ward = 12;
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        com.trialportal.arena.ArenaService.buildPlatform(arena, origin, TrialMode.DEFENSE, ward);
        BlockPos towerPos = origin.offset(0, 1, 0);
        if (!(arena.getBlockEntity(towerPos) instanceof com.trialportal.block.entity.WardPylonBlockEntity tower))
        {
            check(failures, false, "台心拿不到塔的血量池，拆塔判据没跑");
            manager.freeCell(owner);
            return;
        }
        tower.configure(100.0D, owner, "trial_portal:probe");

        DefenseDefinition probe = new DefenseDefinition();
        probe.wardRadius = ward;
        probe.towerDamagePerHit = 2.0D;
        probe.towerHitIntervalTicks = 10;
        probe.towerFocusShare = 1.0D; // 只给这条判据用：要求「这一只必去拆塔」
        TrialSession session = defenseSession();
        session.towerPos = towerPos;

        net.minecraft.world.entity.monster.Vindicator hitter =
                new net.minecraft.world.entity.monster.Vindicator(EntityType.VINDICATOR, arena);
        hitter.setPos(towerPos.getX() + 1.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        arena.addFreshEntity(hitter);
        DefenseFocus assigned = TrialManager.assignDefenseFocus(hitter, probe, session, null,
                net.minecraft.util.RandomSource.create(1L));
        check(failures, assigned == DefenseFocus.TOWER, "share=1 时这只怪应被派去拆塔，实际 " + assigned);
        com.trialportal.arena.TowerAssaultGoal goal = null;
        int goalCount = 0;
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : hitter.goalSelector.getAvailableGoals())
        {
            if (wrapped.getGoal() instanceof com.trialportal.arena.TowerAssaultGoal assault)
            {
                goalCount++;
                goal = assault;
            }
        }
        check(failures, goalCount == 1, "拆塔 goal 应恰好挂上一个，实际 " + goalCount + " 个");
        if (goal == null)
        {
            check(failures, false, "goal 没挂上，后面的扣血对账没跑");
            manager.freeCell(owner);
            return;
        }
        check(failures, goal.canUse(), "怪贴着塔且塔还有血时 goal 该能用（canUse  false 就等于没人拆塔）");
        check(failures, goal.requiresUpdateEveryTick(),
                "扣血按 tick 累计，这个 goal 必须每 tick 更新，否则间隔会悄悄变成两倍");

        // 没走到跟前不许扣血：把它挪到激活半径内但够不着塔的位置
        hitter.setPos(towerPos.getX() + 9.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        goal.start();
        for (int tick = 0; tick < 60; tick++)
        {
            goal.tick();
        }
        check(failures, Math.abs(tower.towerHealth() - 100.0D) < 1.0E-6D,
                "怪离塔 9 格还在扣血（塔血 " + tower.towerHealth() + "）——REACH 没拦住，玩家还没照面塔就没了");

        hitter.setPos(towerPos.getX() + 1.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        goal.start();
        for (int tick = 0; tick < 45; tick++)
        {
            goal.tick();
        }
        // 间隔 10、45 次 tick → 正好 4 下、每下 2 点
        check(failures, Math.abs(tower.towerHealth() - 92.0D) < 1.0E-6D,
                "贴塔打 45 tick 应扣 4 下共 8 点（剩 92），实际塔血 " + tower.towerHealth()
                        + "（间隔或每口伤害算错了）");

        // 两个目标不许打架：怪一旦有仇恨目标，拆塔这条就让路（否则它会边跟人打边掉塔血，玩家看不见原因）
        net.minecraft.world.entity.monster.Vindicator rival =
                new net.minecraft.world.entity.monster.Vindicator(EntityType.VINDICATOR, arena);
        rival.setPos(towerPos.getX() - 2.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        arena.addFreshEntity(rival);
        hitter.setTarget(rival);
        double whileFighting = tower.towerHealth();
        check(failures, !goal.canUse(), "怪已经在跟人打了，拆塔 goal 还开着——两个目标在抢同一次移动");
        for (int tick = 0; tick < 45; tick++)
        {
            goal.tick();
        }
        check(failures, tower.towerHealth() == whileFighting,
                "有仇恨目标期间还在扣塔血（" + whileFighting + "→" + tower.towerHealth() + "）");
        hitter.setTarget(null);
        check(failures, goal.canUse(), "仇恨解除之后应回去拆塔，实际不拆了");
        rival.discard();

        tower.damage(1000.0D);
        check(failures, !goal.canUse(), "塔血归零后 goal 必须关掉，否则这场判负了怪还在啃空气");
        double afterFall = tower.towerHealth();
        for (int tick = 0; tick < 45; tick++)
        {
            goal.tick();
        }
        check(failures, tower.towerHealth() == afterFall, "塔没了之后血量池还在被扣（" + afterFall + "→"
                + tower.towerHealth() + "）");

        hitter.setPos(towerPos.getX() + 60.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        check(failures, !goal.canUse(), "怪出了结界（离塔 60 格）之后 goal 还开着，它会一路追到别人家去");

        // share=0 那一支：一只都不该去拆塔，且必须退回锁玩家（否则场上全是木头人）
        DefenseDefinition none = new DefenseDefinition();
        none.wardRadius = ward;
        none.towerFocusShare = 0.0D;
        net.minecraft.world.entity.monster.Vindicator idle =
                new net.minecraft.world.entity.monster.Vindicator(EntityType.VINDICATOR, arena);
        idle.setPos(towerPos.getX() + 1.0D, towerPos.getY(), towerPos.getZ() + 0.5D);
        arena.addFreshEntity(idle);
        check(failures, TrialManager.assignDefenseFocus(idle, none, session, null,
                        net.minecraft.util.RandomSource.create(2L)) == DefenseFocus.PLAYER,
                "share=0 时应全部归给打人那支");
        int extra = 0;
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : idle.goalSelector.getAvailableGoals())
        {
            if (wrapped.getGoal() instanceof com.trialportal.arena.TowerAssaultGoal)
            {
                extra++;
            }
        }
        check(failures, extra == 0, "share=0 却还挂了 " + extra + " 个拆塔 goal");

        hitter.discard();
        idle.discard();
        com.trialportal.arena.ArenaService.releaseCellAt(arena, cell, 0, new net.minecraft.world.phys.AABB(
                origin.getX() - ward - 1, origin.getY(), origin.getZ() - ward - 1,
                origin.getX() + ward + 2, origin.getY() + com.trialportal.arena.ArenaService.fenceHeight() + 2,
                origin.getZ() + ward + 2));
        manager.freeCell(owner);
    }

    /**
     * 配方只有从 {@code RecipeManager} 里真取出来才算数：JSON 写坏或 id 打错时，原版加载器会把整条配方
     * 丢掉而游戏照跑——玩家那边只是"这个门怎么也合不出来"，一声不响。
     * 输入项还得逐个能匹配到某个已注册物品：id 写错时原版会把它当成空气，配方加载得成却永远摆不出来。
     * <p>
     * 2026-10-10 裁定把方向翻了（§9 第 12 条）：三个玩法门与结界塔退回创造栏，进出大厅收拢成往返门
     * 这一条通道。所以这张账两头都要钉——<b>谁一条都不许有</b>，<b>谁必须恰好一条</b>。
     * 只钉后一半的话，哪天又冒出一条玩法门配方没人知道；只钉前一半，通道自己反倒合不出来。
     */
    private static void probePassageRecipes(MinecraftServer server, List<String> failures)
    {
        List<String> leaked = new ArrayList<>();
        List<String> passage = new ArrayList<>();
        List<String> unsatisfiable = new ArrayList<>();
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : server.getRecipeManager()
                .getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING))
        {
            ItemStack result = recipe.getResultItem(server.registryAccess());
            if (!(result.getItem() instanceof net.minecraft.world.item.BlockItem blockItem))
            {
                continue;
            }
            net.minecraft.world.level.block.Block block = blockItem.getBlock();
            if (block instanceof TrialPortalBlock || block instanceof com.trialportal.block.WardPylonBlock)
            {
                leaked.add(recipe.getId() + " ⇒ " + result.getItem());
                continue;
            }
            if (!(block instanceof com.trialportal.block.ArenaExitBlock))
            {
                continue;
            }
            passage.add(recipe.getId() + " ⇒ " + result.getItem());
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty())
            {
                unsatisfiable.add("往返门的配方取不到输入项，输入项判据会在假数据上跑绿");
                continue;
            }
            for (Ingredient ingredient : ingredients)
            {
                String defect = ingredientDefect(ingredient, server.registryAccess());
                if (defect != null)
                {
                    unsatisfiable.add("往返门：" + defect);
                }
            }
        }
        check(failures, leaked.isEmpty(),
                "这些方块按裁定不该有任何生存配方（入口只有往返门一条，玩法门退回创造栏）：" + leaked);
        check(failures, passage.size() == 1,
                "大厅与野外之间唯一的通道必须有恰好一条生存配方，实际 " + passage.size() + " 条 " + passage
                        + "（一条没有=只能靠创造栏或 /give；多于一条=同一件事两份配方，改哪份都漏一份）");
        check(failures, unsatisfiable.isEmpty(), String.join("；", unsatisfiable)
                + "（写错的 id 就是这个样子：配方加载得成，却永远摆不出来）");
    }

    /**
     * 通道的方向是纯判定，无头门跑得动：野外那台把人送进去，场地里那台把人送出来。
     * 最后一条钉的是这两条判据的立足点——两个方向若依据同一个恒真/恒假的谓词，前面两条就只是
     * 同一条断言换了个写法，方向写反也照绿（TOOTHLESS 抓的就是这个形状）。
     */
    private static void probeReturnPassage(List<String> failures)
    {
        check(failures, com.trialportal.block.ArenaExitBlock.passageFor(Level.OVERWORLD)
                        == com.trialportal.block.ArenaExitBlock.Passage.ENTER_LOBBY,
                "野外的往返门没有指向大厅（点它进不去大厅）");
        check(failures, com.trialportal.block.ArenaExitBlock.passageFor(ArenaService.ARENA)
                        == com.trialportal.block.ArenaExitBlock.Passage.RETURN_HOME,
                "场地里的往返门没有指向离场（点它不回家，会话与攒的奖励都结不掉）");
        check(failures, !ArenaService.isArena(Level.OVERWORLD) && ArenaService.isArena(ArenaService.ARENA),
                "isArena 在野外与场地两个维度键上返回同一个值——上面两条方向判据其实是同一条断言的两种写法，"
                        + "抓不到「方向写反」");
    }

    /**
     * 输入项逐个对账：JSON 里写的那个 id 到底存不存在。
     * <p>
     * 为什么不能用现成的判据：写错的 id 在运行期是<b>静默替换</b>的。本轮实测——
     * {@code {"tag":"minecraft:no_such_tag"}} 会被 Forge 换成 {@code minecraft:barrier}
     * （所以 {@code getItems()} 非空、{@code test()} 也对 barrier 返回 true），
     * {@code {"item":"minecraft:ender_prarl"}} 则是整条配方被原版丢掉（只有覆盖面那条判据看得见）。
     * 两种都不报错，玩家那边只是"这个门合不出来"。所以要回到 id 本身去查注册表。
     *
     * @return null 表示这个输入项没问题
     */
    private static String ingredientDefect(Ingredient ingredient, net.minecraft.core.RegistryAccess access)
    {
        com.google.gson.JsonElement json = ingredient.toJson();
        if (!json.isJsonObject())
        {
            return null; // 复合/自定义输入项（条件、差量那一类），不认识就放过
        }
        com.google.gson.JsonObject object = json.getAsJsonObject();
        if (object.has("item"))
        {
            String written = object.get("item").getAsString();
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(written);
            net.minecraft.world.item.Item item = id == null ? null
                    : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR)
            {
                return "输入项的物品 " + written + " 不在物品注册表里（原版会把它换成空气，这一槽永远摆不出来）";
            }
            return null;
        }
        if (!object.has("tag"))
        {
            return null;
        }
        String written = object.get("tag").getAsString();
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(written);
        if (id == null)
        {
            return "输入项的 tag 写法不合法（" + written + "）";
        }
        // 查注册表访问里的物品注册表：数据包定义的标签在这里，BuiltInRegistries 那份看不到
        java.util.Optional<? extends net.minecraft.core.HolderSet.Named<net.minecraft.world.item.Item>> tagged =
                access.registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
                        .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, id));
        if (tagged.isEmpty() || tagged.get().size() == 0)
        {
            return "输入项的 tag " + written + " 里没有任何物品（Forge 会塞一个 barrier 顶替：配方加载得成，"
                    + "但生存里没人摆得出来）";
        }
        return null;
    }


    /**
     * 判据只有"玩家走得到"：从上一间屋里任意一格出发做洪水填充，看能不能真的踏进新房中心。
     * 断言刻意<b>不</b>复刻走廊的画法——把实现抄进断言，实现错了断言会跟着一起绿（这条踩过三次）。
     */
    private static void probeJoggedChain(ServerLevel arenaLevel, List<String> failures, RoomDefinition room,
                                         int wantLateral, int gap, int cell, String label)
    {
        BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        BlockPos originA = cellOrigin.offset(-room.width() / 2, 0,
                com.trialportal.arena.ArenaService.radius() + 4);
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, originA,
                net.minecraft.util.RandomSource.create(11L), room.paletteFor(11L));
        int reachA = originA.getZ() + room.depth() - cellOrigin.getZ();

        int limit = com.trialportal.arena.ArenaService.lateralLimit(room.width());
        int lateral = Math.max(-limit, Math.min(limit, wantLateral));
        check(failures, lateral != 0,
                "lateral " + wantLateral + " 被横向余量夹成 0（limit=" + limit + "），这套布局永远不可能生效");
        BlockPos originB = cellOrigin.offset(-room.width() / 2 + lateral, 0, reachA + gap);
        net.minecraft.world.phys.AABB link = com.trialportal.arena.RoomBuilder.buildLink(arenaLevel,
                cellOrigin.getX(), cellOrigin.getZ() + reachA, cellOrigin.getX() + lateral, originB.getZ() - 1,
                com.trialportal.arena.RoomBuilder.entryFloor(), com.trialportal.arena.RoomBuilder.entryWall(),
                com.trialportal.arena.RoomBuilder.defaultLight(),
                com.trialportal.arena.RoomBuilder.defaultLightSpacing());
        com.trialportal.arena.RoomBuilder.Built builtB = com.trialportal.arena.RoomBuilder.build(
                arenaLevel, room, originB, net.minecraft.util.RandomSource.create(12L), room.paletteFor(12L));

        int doorX = originB.getX() + room.width() / 2;
        check(failures, canWalkFromRoom(arenaLevel, originA, room, doorX, originB.getZ() + room.depth() / 2),
                label + "：" + gap + " 格间隙 + 横移 " + lateral + " 格，从上一间走不进新房（走廊拐角或新房外墙把它封死了）");
        check(failures, !isGround(arenaLevel, new BlockPos(originA.getX(), com.trialportal.arena.ArenaService.FLOOR_Y,
                        originB.getZ() + 1)),
                label + "：新房还压在老轴线上，lateral 没真的进落点");
        int badPoints = 0;
        for (BlockPos point : builtB.spawnPoints())
        {
            if (!isGround(arenaLevel, point.below()) || !arenaLevel.getBlockState(point).isAir())
            {
                badPoints++;
            }
        }
        check(failures, badPoints == 0, label + "：横移之后的站怪点有 " + badPoints + " 个脚下不实心或被挡");

        // 拆场：横移的房 + 折线走廊都得跟着一起没，虚空里不许留桥
        int reachB = originB.getZ() + room.depth() - cellOrigin.getZ();
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell, reachB,
                link.minmax(builtB.region()));
        int bendRow = cellOrigin.getZ() + reachA + 1;
        int jogX = (cellOrigin.getX() + doorX) / 2;
        check(failures, countRoomBlocks(arenaLevel, originA, room) == 0
                        && countRoomBlocks(arenaLevel, originB, room) == 0,
                label + "：横移过的房拆不干净");
        check(failures, arenaLevel.getBlockState(new BlockPos(jogX, com.trialportal.arena.ArenaService.FLOOR_Y,
                        bendRow)).isAir(),
                label + "：折线走廊留在虚空里（拆场只按房间盒子算，没并上走廊的足迹）");
        check(failures, !hasForcedChunkIn(arenaLevel, originA.offset(-3, 0, -3),
                        originB.offset(room.width() + 3, 0, room.depth() + 3)),
                label + "：横移走廊 force 过的区块拆完没还回去");
    }

    /** 走廊足迹里"有地板却站不住人"的格数——挡墙不该落在车道上，落在上面玩家就会撞墙。 */
    private static int blockedLaneCells(ServerLevel level, net.minecraft.world.phys.AABB link)
    {
        int floorY = com.trialportal.arena.ArenaService.FLOOR_Y;
        int blocked = 0;
        for (int x = (int) Math.floor(link.minX); x < (int) Math.ceil(link.maxX); x++)
        {
            for (int z = (int) Math.floor(link.minZ); z < (int) Math.ceil(link.maxZ); z++)
            {
                BlockPos pos = new BlockPos(x, floorY, z);
                if (isGround(level, pos) && (!level.getBlockState(pos.above(1)).isAir()
                        || !level.getBlockState(pos.above(2)).isAir()))
                {
                    blocked++;
                }
            }
        }
        return blocked;
    }

    /** 库里第一套带横移的落点；没有就返回 null（让调用方把"判据没跑"报成失败）。 */
    private static RoomDefinition.Layout firstJoggedLayout(RoomDefinition room)
    {
        if (room == null)
        {
            return null;
        }
        for (RoomDefinition.Layout layout : room.layouts)
        {
            if (layout.lateral != 0)
            {
                return layout;
            }
        }
        return null;
    }

    /**
     * 站在上一间屋里的任意一格，能不能走到目标格。
     * 起点铺满整间屋是故意的——遮挡物会随机占掉门口那一格，钉死一个起点就成了碰运气的断言。
     */
    private static boolean canWalkFromRoom(ServerLevel level, BlockPos fromOrigin, RoomDefinition from,
                                           int toX, int toZ)
    {
        int floorY = com.trialportal.arena.ArenaService.FLOOR_Y;
        List<BlockPos> starts = new ArrayList<>();
        for (int dx = 0; dx < from.width(); dx++)
        {
            for (int dz = 0; dz < from.depth(); dz++)
            {
                starts.add(fromOrigin.offset(dx, 0, dz));
            }
        }
        return reachable(level, starts, toX, toZ,
                Math.min(fromOrigin.getX(), toX) - 24, Math.max(fromOrigin.getX() + from.width(), toX) + 24,
                Math.min(fromOrigin.getZ(), toZ) - 4, Math.max(fromOrigin.getZ() + from.depth(), toZ) + 4);
    }

    private static boolean standable(ServerLevel level, BlockPos floorPos)
    {
        return isGround(level, floorPos) && level.getBlockState(floorPos.above(1)).isAir()
                && level.getBlockState(floorPos.above(2)).isAir();
    }

    private static long key(int x, int z)
    {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * {@code xFrom == xTo} 时折线必须退化成原来那条直走廊：3 格宽车道 + 两侧各一道挡墙。
     * 这条是"重写走廊原语没把老形状改掉"的对账，横移布局上线以后老路径还在，所以它得有牙。
     */
    private static void probeStraightLinkShape(ServerLevel arenaLevel, List<String> failures)
    {
        int cell = 17;
        BlockPos cellOrigin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        int zFrom = cellOrigin.getZ() + com.trialportal.arena.ArenaService.radius() + 4;
        com.trialportal.arena.RoomBuilder.buildLink(arenaLevel, cellOrigin.getX(), zFrom,
                cellOrigin.getX(), zFrom + 6, com.trialportal.arena.RoomBuilder.entryFloor(),
                com.trialportal.arena.RoomBuilder.entryWall(), com.trialportal.arena.RoomBuilder.defaultLight(),
                com.trialportal.arena.RoomBuilder.defaultLightSpacing());
        int mid = zFrom + 3;
        int laneBad = 0;
        int railBad = 0;
        for (int dx = -2; dx <= 2; dx++)
        {
            BlockPos pos = new BlockPos(cellOrigin.getX() + dx, com.trialportal.arena.ArenaService.FLOOR_Y, mid);
            boolean lane = Math.abs(dx) <= 1;
            boolean solid = isGround(arenaLevel, pos);
            boolean blockedAbove = !arenaLevel.getBlockState(pos.above(2)).isAir();
            if (lane)
            {
                // 中间三格该是"有地板、头顶通空"
                if (!solid || blockedAbove)
                {
                    laneBad++;
                }
            }
            else if (solid || !blockedAbove)
            {
                // 两侧两格该是"没地板、半空一道墙"
                railBad++;
            }
        }
        check(failures, laneBad == 0, "直走廊的车道不再是 3 格通空（" + laneBad + " 格不对）");
        check(failures, railBad == 0, "直走廊两侧的挡墙不见了（" + railBad + " 格不对），玩家会从走廊边走进虚空");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell,
                mid + 4 - cellOrigin.getZ());
    }

    /** layouts 的两条校验：横移没留间隙会被新房墙封死；整份列表都是直行就是没配。 */
    private static void probeLayoutRulesRejected(List<String> failures)
    {
        String jogWithoutGap = layoutRoom(0, 6).resolve("trial_portal:probe_jog_gap");
        check(failures, jogWithoutGap != null,
                "lateral 非 0 而 gap=0 的布局没被拦：走廊横移段会压在新房外墙底下被封死");
        String gapTooBig = layoutRoom(40, 4).resolve("trial_portal:probe_jog_range");
        check(failures, gapTooBig != null, "gap 超过上限没被拦（进深会无限长，强制加载跟着涨）");
        RoomDefinition inert = new RoomDefinition();
        inert.size = List.of(9, 5, 9);
        inert.layouts.add(layout(0, 0));
        inert.layouts.add(layout(0, 0));
        String allStraight = inert.resolve("trial_portal:probe_jog_inert");
        check(failures, allStraight != null,
                "layouts 全是直行却没人报错（多半是键名拼错了，配了等于没配）");
        String sane = layoutRoom(4, 6).resolve("trial_portal:probe_jog_sane");
        check(failures, sane == null, "合法的 layouts 被误判：" + sane);
    }

    private static RoomDefinition layoutRoom(int gap, int lateral)
    {
        RoomDefinition room = new RoomDefinition();
        room.size = List.of(9, 5, 9);
        room.layouts.add(layout(gap, lateral));
        return room;
    }

    private static RoomDefinition.Layout layout(int gap, int lateral)
    {
        RoomDefinition.Layout layout = new RoomDefinition.Layout();
        layout.gap = gap;
        layout.lateral = lateral;
        return layout;
    }

    /**
     * 外观与落点是两条独立的选择：都取种子最低位、列表长度又一样时，两者会永远锁成同一对，
     * 组合数直接砍一半（"苔石厅"必定走吊桥）。这条逐间房钉住"组合要跑满"。
     * 只查一间房是不够的：2×3 那间即便两条流同源，靠互质也能凑满 6 种——真正会露馅的是 2×2。
     */
    private static void probeVariantLayoutPairing(List<String> failures)
    {
        int checked = 0;
        for (RoomDefinition room : RoomLibrary.snapshot().values())
        {
            if (room.variants.size() < 2 || room.layouts.size() < 2)
            {
                continue;
            }
            checked++;
            Set<String> pairs = new HashSet<>();
            for (int i = 0; i < 96; i++)
            {
                long seed = i * 31L + 7L; // 与 TrialManager 的 layoutSeed 同一种步长
                RoomDefinition.Palette palette = room.paletteFor(seed);
                RoomDefinition.Layout layout = room.layoutFor(seed);
                pairs.add(palette.floor + "+" + palette.wall + "/" + layout.gap + "/" + layout.lateral);
            }
            int full = room.variants.size() * room.layouts.size();
            check(failures, pairs.size() >= full,
                    room.id() + " 的外观×落点只跑出 " + pairs.size() + " 种组合，全组合应有 " + full
                            + " 种：两条列表取了同一段比特，配对被锁死");
        }
        check(failures, checked >= 4,
                "只有 " + checked + " 间房同时配了 variants 与 layouts，组合数判据覆盖面不足");
    }

    /**
     * 方块 ID 写法合法但注册表里根本没有——离线门看不见（它没有活注册表），运行期只会 warn 一句
     * 然后静默回退成默认材质。这一轮我自己就踩了一次：把柱子写成 polished_blackstone_pillar，
     * 黑石家族里根本没这块，房间会悄悄变回雕纹黑石砖。所以这条必须在有注册表的进程里查。
     */
    private static void probeRoomBlocksResolve(List<String> failures)
    {
        List<String> missing = new ArrayList<>();
        for (RoomDefinition room : RoomLibrary.snapshot().values())
        {
            List<String> candidates = new ArrayList<>();
            candidates.add(room.floor);
            candidates.add(room.wall);
            candidates.add(room.ceilingBlock);
            candidates.add(room.pillarBlock);
            candidates.add(room.lightBlock);
            for (RoomDefinition.CoverEntry cover : room.cover)
            {
                candidates.add(cover.block);
            }
            for (RoomDefinition.Partition partition : room.partitions)
            {
                candidates.add(partition.block);
            }
            for (RoomDefinition.Palette variant : room.variants)
            {
                candidates.add(variant.floor);
                candidates.add(variant.wall);
                candidates.add(variant.ceilingBlock);
                candidates.add(variant.pillarBlock);
                candidates.add(variant.lightBlock);
                candidates.add(variant.patternBlock);
            }
            for (String id : candidates)
            {
                if (id == null || id.isBlank())
                {
                    continue; // 空串＝"用默认"，不是引用
                }
                ResourceLocation key = ResourceLocation.tryParse(id);
                if (key == null || !net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(key))
                {
                    missing.add(room.id() + " → " + id);
                }
            }
        }
        check(failures, missing.isEmpty(),
                "有房间方块在注册表里不存在，运行期会静默回退成默认材质：" + String.join("、", missing));
    }

    /**
     * 同一颗种子必须建出同一份布局：遮挡物以前直接吃 {@code level.getRandom()}，
     * 世界随机流每 tick 都在动，"同一场重进还是这个样"根本谈不上，#24 的外观变体也没地方挂。
     */
    private static void probeLayoutIsSeedDriven(ServerLevel arenaLevel, List<String> failures)
    {
        RoomDefinition room = null;
        for (RoomDefinition candidate : RoomLibrary.snapshot().values())
        {
            if (!candidate.cover.isEmpty())
            {
                room = candidate;
                break;
            }
        }
        if (room == null)
        {
            check(failures, false, "房间库里没有一份带 cover 的房，种子判据等于没跑");
            return;
        }
        int offset = com.trialportal.arena.ArenaService.radius() + 4;
        BlockPos a = com.trialportal.arena.ArenaService.cellOrigin(10).offset(-room.width() / 2, 0, offset);
        BlockPos b = com.trialportal.arena.ArenaService.cellOrigin(11).offset(-room.width() / 2, 0, offset);
        long seed = 9001L;
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, a, net.minecraft.util.RandomSource.create(seed),
                room.paletteFor(seed));
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, b, net.minecraft.util.RandomSource.create(seed),
                room.paletteFor(seed));
        int mismatch = 0;
        for (int dx = 0; dx <= room.width() + 1; dx++)
        {
            for (int dz = 0; dz <= room.depth() + 1; dz++)
            {
                for (int dy = 1; dy <= room.height() + 1; dy++)
                {
                    if (!arenaLevel.getBlockState(a.offset(dx, dy, dz))
                            .is(arenaLevel.getBlockState(b.offset(dx, dy, dz)).getBlock()))
                    {
                        mismatch++;
                    }
                }
            }
        }
        check(failures, mismatch == 0,
                "同一颗种子两次建出的 " + room.id() + " 有 " + mismatch + " 格不一致（cover 没走会话种子？）");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 10);
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, 11);
    }

    /**
     * 拆场必须按"本场真实写过的区域"算，不按房间表声明的 size 反推。
     * 判据是两侧都有牙的：先证明只按声明盒子拆**留得住**那格超纲装饰（否则探针没构造出情况），
     * 再证明按记账区域拆**清得掉**它并把区块还回去。
     */
    private static void probeTeardownUsesRecordedBox(ServerLevel arenaLevel, MinecraftServer server,
                                                     List<String> failures)
    {
        TrialManager manager = TrialManager.get(server);
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes(
                "trial_portal:selftest:decor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int cell = manager.allocateCell(player, TrialMode.TRIAL, null);
        BlockPos origin = com.trialportal.arena.ArenaService.cellOrigin(cell);
        com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, origin, TrialMode.TRIAL);
        net.minecraft.world.phys.AABB declared = com.trialportal.arena.ArenaService.cellBox(cell, 0);
        BlockPos decor = new BlockPos((int) declared.maxX + 3, com.trialportal.arena.ArenaService.FLOOR_Y,
                (int) declared.minZ);
        long decorChunk = net.minecraft.world.level.ChunkPos.asLong(decor.getX() >> 4, decor.getZ() >> 4);
        arenaLevel.setChunkForced(decor.getX() >> 4, decor.getZ() >> 4, true);
        arenaLevel.setBlock(decor, net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        check(failures, !arenaLevel.getBlockState(decor).isAir(),
                "探针没能把超纲装饰写进场地（" + decor.toShortString() + "），这条判据是空的");
        com.trialportal.arena.ArenaService.releaseCellAt(arenaLevel, cell, 0);
        check(failures, !arenaLevel.getBlockState(decor).isAir()
                        && arenaLevel.getForcedChunks().contains(decorChunk),
                "探针失效：只按声明尺寸拆场，居然把超纲的装饰和它的区块一起带走了");
        manager.recordBuilt(player, 0, new net.minecraft.world.phys.AABB(decor.getX(), decor.getY(), decor.getZ(),
                decor.getX() + 1, decor.getY() + 1, decor.getZ() + 1));
        com.trialportal.arena.ArenaService.releaseCell(arenaLevel, manager, player, cell);
        check(failures, arenaLevel.getBlockState(decor).isAir()
                        && !arenaLevel.getForcedChunks().contains(decorChunk),
                "按记账里的真实区域拆场没清干净：装饰还在="
                        + !arenaLevel.getBlockState(decor).isAir()
                        + "，区块没还回去=" + arenaLevel.getForcedChunks().contains(decorChunk));
        manager.freeCell(player);
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
        com.trialportal.arena.RoomBuilder.Built probeBuilt = com.trialportal.arena.RoomBuilder.build(
                arenaLevel, room, origin, net.minecraft.util.RandomSource.create(999L), room.paletteFor(999L));
        manager.recordBuilt(probePlayer, origin.getZ() + room.depth() - cellOrigin.getZ(), probeBuilt.region());
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
