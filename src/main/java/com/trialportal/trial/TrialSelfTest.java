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
            int gap = Math.max(1, (radius - 1) / 3);
            int row = -(radius - 1);
            check(failures, arenaLevel.getBlockState(lobby.offset(-3 * gap, 1, row)).getBlock()
                            instanceof com.trialportal.block.TrialPortalBlock,
                    "大厅北侧台沿应摆出闯关终端（终端不许占场地中央）");
            check(failures, arenaLevel.getBlockState(lobby.offset(3 * gap, 1, row)).getBlock()
                            instanceof com.trialportal.block.ArenaExitBlock,
                    "大厅北侧台沿应摆出返回终端");
            check(failures, arenaLevel.getBlockState(lobby.offset(0, 1, 0)).isAir()
                            && arenaLevel.getBlockState(lobby.offset(0, 1, 4)).isAir(),
                    "场地中央不许有功能方块：挡打斗区，也被玩家当成'重复出现的传送门'");
            // 大厅永远不拆台，旧布局/异常留下的终端必须被下一次建台抹平（用户报的"重复出现传送方块"）
            arenaLevel.setBlock(lobby.offset(2, 1, 3),
                    com.trialportal.registry.ModBlocks.TRIAL_PORTAL.get().defaultBlockState(), 3);
            int before = countTerminals(arenaLevel, lobby, radius);
            com.trialportal.arena.ArenaService.buildPlatform(arenaLevel, lobby, null);
            int after = countTerminals(arenaLevel, lobby, radius);
            check(failures, before == 5 && after == 4,
                    "多余终端必须在重建时被抹平：埋一个之后应有 5 个，重建后只剩 4 个（实际 "
                            + before + " → " + after + "）");
            // 庙（#23）：门前不许堵、柱子要真立起来、加了装饰之后中央依旧空
            int blocked = 0;
            int columns = 0;
            for (int slot = -1; slot <= 1; slot++)
            {
                int dx = -gap + slot * 2 * gap;
                if (!arenaLevel.getBlockState(lobby.offset(dx, 1, row + 1)).isAir())
                {
                    blocked++;
                }
                for (int dy = 1; dy <= 5; dy++)
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
            }
            check(failures, blocked == 0, "庙把 " + blocked + " 扇门的门前一格堵住了，玩家右键会点不到门");
            check(failures, columns >= 30,
                    "大厅庙柱只数到 " + columns + " 格（三对柱子各 5 格，应为 30）——装饰没建出来");
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
        int newCell = manager.allocateCell(player, TrialMode.TRIAL, arenaLevel);
        check(failures, built > 0 && newCell != oldCell,
                "探针没能造出旧隔间（建了 " + built + " 块，跨段新编号 " + newCell + "，旧编号 " + oldCell + "）");
        int leftAfter = countPlatformBlocks(arenaLevel, oldOrigin, radius);
        check(failures, leftAfter == 0,
                "换玩法后旧隔间 " + oldCell + " 还留着 " + leftAfter + " 块平台：编号会被重新分给别的玩家");
        manager.freeCell(player);
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
        room.mobCap = 1;
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
                net.minecraft.util.RandomSource.create(4711L));
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
        room.mobCap = 1;
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
                net.minecraft.util.RandomSource.create(1313L));
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
            candidates.add(room.pillarBlock);
            candidates.add(room.lightBlock);
            for (RoomDefinition.CoverEntry cover : room.cover)
            {
                candidates.add(cover.block);
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
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, a, net.minecraft.util.RandomSource.create(seed));
        com.trialportal.arena.RoomBuilder.build(arenaLevel, room, b, net.minecraft.util.RandomSource.create(seed));
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
                arenaLevel, room, origin, net.minecraft.util.RandomSource.create(999L));
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
