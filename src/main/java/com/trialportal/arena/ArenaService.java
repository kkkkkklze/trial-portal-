package com.trialportal.arena;

import com.mojang.logging.LogUtils;
import com.trialportal.TrialPortal;
import com.trialportal.block.ArenaExitBlock;
import com.trialportal.block.TrialPortalBlock;
import com.trialportal.config.TrialConfig;
import com.trialportal.registry.ModBlocks;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialFeedback;
import com.trialportal.trial.TrialLibrary;
import com.trialportal.trial.TrialManager;
import com.trialportal.trial.TrialMode;
import com.trialportal.trial.TrialSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 试炼场地维度：大厅 + 每人一个隔间。
 * <p>
 * 平台全部 {@code setBlock} 程序化生成，不用原版 structure 模板（1.20.1 的模板要二进制 .nbt 资产，
 * 既不可 diff 也进不了 checkTrialData 的校验）。因此建台必须幂等：重复进入同一个隔间不会越铺越厚。
 * <p>
 * 谁占着哪个隔间、该送回哪儿，一律记在 {@link TrialManager} 的存档里，不放进程内 static Map——
 * CompactMachines 的教训是「只断边不删房」留下孤儿切片，而 static Map 重启即丢是同一个病。
 */
public final class ArenaService
{
    public static final ResourceKey<Level> ARENA =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, "arena"));

    /** 平台所在层；玩家站的位置是这一层 +1。 */
    public static final int FLOOR_Y = 64;
    /** cell 0 永远是大厅。 */
    public static final int LOBBY_CELL = 0;

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** 开服清扫排到下一轮的隔间：怪要等实体注入才看得见，强制加载也得等那一刻之后再松手。 */
    private record PendingRelease(int cell, int reach, UUID playerId) {}

    private static final List<PendingRelease> PENDING_RELEASES = new ArrayList<>();

    private ArenaService() {}

    @Nullable
    public static ServerLevel level(MinecraftServer server)
    {
        return server.getLevel(ARENA);
    }

    public static boolean isArena(@Nullable ResourceKey<Level> key)
    {
        return ARENA.equals(key);
    }

    public static boolean isInArena(ServerPlayer player)
    {
        return isArena(player.level().dimension());
    }

    public static int radius()
    {
        return Math.max(1, TrialConfig.ARENA_PLATFORM_RADIUS.get());
    }

    /** 围栏高度。至少 2 才关得住会跳 1 格的怪，配置再高也只是玻璃墙。 */
    public static int fenceHeight()
    {
        return Math.max(2, TrialConfig.ARENA_FENCE_HEIGHT.get());
    }

    /** 间隔至少要装下两个平台加围栏，否则隔壁间会挨在一起。 */
    public static int spacing()
    {
        return Math.max(radius() * 2 + 8, TrialConfig.ARENA_CELL_SPACING.get());
    }

    public static BlockPos cellOrigin(int cell)
    {
        return new BlockPos((int) ((long) cell * spacing()), FLOOR_Y, 0);
    }

    // ------------------------------------------------------------------ 进出

    /** 野外右键传送门 = 进大厅。记下回返点，之后结算/放弃/中止都回这里。 */
    public static boolean enterLobby(ServerPlayer player)
    {
        ServerLevel arena = level(player.server);
        if (arena == null)
        {
            TrialFeedback.failure(player, "试炼场地维度没有加载（数据包被禁用或 dimension/arena.json 缺失）");
            LOGGER.warn("[trial_portal] 进大厅失败：找不到维度 {}", ARENA.location());
            return false;
        }
        TrialManager manager = TrialManager.get(player.server);
        UUID uuid = player.getUUID();
        TrialSession session = manager.sessionOf(uuid);
        manager.recordHome(uuid, new TrialManager.HomePos(player.level().dimension(), player.getX(), player.getY(),
                player.getZ(), player.getYRot(), player.getXRot()));
        BlockPos origin = cellOrigin(session == null ? LOBBY_CELL : manager.cellOf(uuid));
        buildPlatform(arena, origin, session == null ? null : modeOf(session));
        teleportSafe(arena, player, origin, session == null ? 180.0F : player.getYRot(),
                session == null ? 0.0F : player.getXRot());
        if (session == null)
        {
            TrialFeedback.title(player, "试炼大厅", "右键对应传送门选择玩法");
        }
        return true;
    }

    /**
     * 野外那扇门的语义：直连它自己的玩法。已有同模式会话就把自己送回隔间继续，
     * 不同模式则拒绝（不能一句话把两场并成一场）。
     */
    /**
     * 点某扇门的语义：直连它自己的玩法。已有会话时——同模式=回自己隔间继续，异模式=拒绝
     * （不能一句话把两场并成一场）。副本没有波次定义，所以 {@code definition} 允许为 null。
     */
    public static boolean enterPlay(ServerPlayer player, TrialMode mode, @Nullable TrialDefinition definition)
    {
        TrialManager manager = TrialManager.get(player.server);
        TrialSession session = manager.sessionOf(player.getUUID());
        if (session == null)
        {
            if (mode.isSustained())
            {
                // 闯关是「选副本 + 选难度」的玩法，所以点门先弹界面；清单为空才直接报错
                if (com.trialportal.trial.ChallengeLibrary.ids().isEmpty())
                {
                    TrialFeedback.failure(player, "challenges/ 里没有任何副本，闯关玩法没内容");
                    return false;
                }
                com.trialportal.network.TrialNetwork.openPicker(player);
                return true;
            }
            if (definition == null)
            {
                TrialFeedback.failure(player, "这个传送门没有可用的试炼定义（mode=" + mode.jsonKey() + "）");
                return false;
            }
            return beginSession(player, definition);
        }
        if (modeOf(session) != mode)
        {
            TrialDefinition own = TrialLibrary.get(session.definitionId);
            String name = own == null ? session.challengeId : own.displayName;
            TrialFeedback.failure(player, "你当前那一场是「" + name + "」，先用返回终端结算再开别的");
            return false;
        }
        ServerLevel arena = level(player.server);
        if (arena == null)
        {
            TrialFeedback.failure(player, "试炼场地维度没加载，无法回到隔间");
            return false;
        }
        buildPlatform(arena, cellOrigin(session.cell), mode);
        teleportSafe(arena, player, cellOrigin(session.cell), 180.0F, 0.0F);
        TrialFeedback.chat(player, "[试炼] 已把你的隔间重新铺好，回到上一场继续");
        return true;
    }

    private static TrialMode modeOf(TrialSession session)
    {
        if (session.challengeId != null)
        {
            return TrialMode.CHALLENGE; // 副本会话的内容不在 trials/ 里，按玩法归 CHALLENGE
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        return definition == null ? null : definition.mode;
    }

    /**
     * 开一局资源副本（CHALLENGE 玩法）。与 {@link #beginSession} 同一条隔间链路，
     * 只是内容来自 {@code challenges/}、玩法循环不计波次。
     */
    public static boolean beginDungeon(ServerPlayer player, com.trialportal.trial.ChallengeDefinition challenge,
                                       String difficultyKey)
    {
        ServerLevel arena = level(player.server);
        if (arena == null)
        {
            TrialFeedback.failure(player, "试炼场地维度没加载，无法开局");
            return false;
        }
        TrialManager manager = TrialManager.get(player.server);
        if (manager.hasSession(player.getUUID()))
        {
            // 必须先拒，再谈动世界：界面里连点两下"确认"就会第二次走到这里，
            // 放行的话下面的 allocateCell 会把他正站着的上一场隔间直接拆掉。
            TrialFeedback.failure(player, "你已经有一场试炼在进行中，先用返回终端结算");
            return false;
        }
        recordReturnPoint(manager, player);
        int cell = manager.allocateCell(player.getUUID(), TrialMode.CHALLENGE, arena);
        BlockPos origin = cellOrigin(cell);
        buildPlatform(arena, origin, TrialMode.CHALLENGE);
        sweepCellMobs(arena, cell, manager.reachOf(player.getUUID())); // 兜底：正常清怪在上一场离场那一次
        if (!manager.startDungeon(player, challenge, difficultyKey, origin, cell, arena.dimension()))
        {
            releaseCell(arena, manager, player.getUUID(), cell); // 台已经铺了，只销记账会留下一间没人认领的房
            return false;
        }
        teleportSafe(arena, player, origin, 180.0F, 0.0F);
        return true;
    }

    /** 大厅里右键某个模式的门 = 在自己的隔间开一场。 */
    public static boolean beginSession(ServerPlayer player, TrialDefinition definition)
    {
        ServerLevel arena = level(player.server);
        if (arena == null)
        {
            TrialFeedback.failure(player, "试炼场地维度没加载，无法开局");
            return false;
        }
        TrialManager manager = TrialManager.get(player.server);
        if (manager.hasSession(player.getUUID()))
        {
            TrialFeedback.failure(player, "你已经有一场试炼在进行中，先用返回终端结算");
            return false;
        }
        recordReturnPoint(manager, player);
        int cell = manager.allocateCell(player.getUUID(), definition.mode, arena);
        BlockPos origin = cellOrigin(cell);
        buildPlatform(arena, origin, definition.mode);
        sweepCellMobs(arena, cell, manager.reachOf(player.getUUID())); // 兜底：正常清怪在上一场离场那一次
        if (!manager.start(player, definition, origin, cell, arena.dimension()))
        {
            releaseCell(arena, manager, player.getUUID(), cell);
            TrialFeedback.failure(player, "开局失败：见服务端日志（trial_portal）");
            return false;
        }
        // 开局之后必须把玩家送进隔间：判定中心已经跟着 origin 走了，人还留在大厅会在下一 tick 被判离开范围
        teleportSafe(arena, player, origin, 180.0F, 0.0F);
        return true;
    }

    /** 结算 / 放弃 / 中止后回野外。 */
    public static void returnHome(ServerPlayer player)
    {
        UUID uuid = player.getUUID();
        ServerLevel arena = level(player.server);
        TrialManager manager = TrialManager.get(player.server);
        TrialManager.HomePos home = manager.takeHome(uuid);
        int cell = manager.cellOf(uuid);
        // 先把人送走再拆台：反过来会让玩家站在被抽掉平台的虚空里掉死
        ServerLevel target = home == null ? null : player.server.getLevel(home.dimension());
        if (target == null)
        {
            target = player.server.overworld();
        }
        BlockPos desired = home == null ? target.getSharedSpawnPos() : BlockPos.containing(home.x(), home.y(), home.z());
        float yaw = home == null ? player.getYRot() : home.yaw();
        float pitch = home == null ? player.getXRot() : home.pitch();
        teleportSafe(target, player, desired, yaw, pitch);
        LOGGER.info("[trial_portal] 送回 {} @ {}（{}）",
                home == null ? "世界出生点（没有回返记录）" : home.dimension().location(),
                desired.toShortString(), player.getScoreboardName());
        releaseCell(arena, manager, uuid, cell);
    }

    /** 拆掉这个玩家占的隔间（大厅永不拆）并清掉记账。进深从记账里取，不由调用点传——漏传一次就会留下一间房。 */
    public static void releaseCell(@Nullable ServerLevel arena, TrialManager manager, UUID playerId, int cell)
    {
        int reach = manager.reachOf(playerId); // 先读进深：freeCell 会把它一起销掉
        if (arena == null)
        {
            // 维度没加载：宁可留着记账。编号一销，那间房就再没人认得它了；留着下次开服清扫还能收。
            LOGGER.warn("[trial_portal] 试炼场地维度没加载，{} 的隔间 {} 留待下次清扫", playerId, cell);
            return;
        }
        if (cell != LOBBY_CELL)
        {
            if (Boolean.TRUE.equals(TrialConfig.ARENA_CLEAR_CELL_ON_END.get()))
            {
                releaseCellAt(arena, cell, reach);
            }
            else
            {
                sweepCellMobs(arena, cell, reach); // 留着平台不等于留着怪
                BlockPos[] bounds = cellBounds(cell, reach);
                setRegionForced(arena, bounds[0], bounds[1], false); // 房留在盘上，区块要还回去，否则每场攒一叠常驻
            }
        }
        manager.freeCell(playerId);
    }

    public static void releaseCellAt(ServerLevel arena, int cell)
    {
        releaseCellAt(arena, cell, 0);
    }

    /**
     * @param reach 试炼玩法沿 +Z 方向多建出来的进深（房间与走廊）；0 = 只有入口平台
     */
    public static void releaseCellAt(ServerLevel arena, int cell, int reach)
    {
        if (cell == LOBBY_CELL)
        {
            return;
        }
        BlockPos[] bounds = cellBounds(cell, reach);
        setRegionForced(arena, bounds[0], bounds[1], true);
        sweepCellMobs(arena, cell, reach);
        clearCellBlocks(arena, bounds[0], bounds[1]);
        setRegionForced(arena, bounds[0], bounds[1], false);
    }

    /**
     * 拆场的方块盒子。清场盒子要按房间里最大的那间算：房可以比平台宽、比默认高度高，走廊还往 +Z
     * 接出去 reach 格。只按平台半径清会把房间两侧外墙留在虚空里，并且把 RoomBuilder 强制加载的
     * 区块永久留在场上。
     */
    private static BlockPos[] cellBounds(int cell, int reach)
    {
        BlockPos origin = cellOrigin(cell);
        int halfWidth = cellHalfFootprint();
        int topY = Math.max(10, RoomLibrary.maxHeight() + 2);
        int depth = halfWidth + Math.max(0, reach) + 8;
        return new BlockPos[] {origin.offset(-halfWidth, -1, -halfWidth), origin.offset(halfWidth, topY, depth)};
    }

    private static void clearCellBlocks(ServerLevel arena, BlockPos from, BlockPos to)
    {
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
        {
            if (arena.getBlockState(pos).isAir())
            {
                continue;
            }
            arena.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void setRegionForced(ServerLevel level, BlockPos from, BlockPos to, boolean forced)
    {
        int cx0 = from.getX() >> 4;
        int cx1 = to.getX() >> 4;
        int cz0 = from.getZ() >> 4;
        int cz1 = to.getZ() >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
        {
            for (int cz = cz0; cz <= cz1; cz++)
            {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    /**
     * 启动后清扫孤儿隔间：存档里记着有人占格子，但那个人已经没有会话了
     * （重启、崩溃、被踢都会留下这种状态）。不清就会在虚空里越攒越多没人认领的平台。
     * <p>
     * 分两步走：这一步只加载区块和抽方块，怪和强制加载留给 {@link #tickPendingReleases} 下一轮收。
     * 原因是刚 force 的区块里，存档怪要等实体注入跑完才进得了实体查询——当场扫等于没扫。
     * 记账也留到第二步才销：中途再崩一次，下一次开服还会认得这些格子。
     *
     * @return 排队回收的隔间数
     */
    public static int sweepOrphanCells(MinecraftServer server)
    {
        ServerLevel arena = level(server);
        if (arena == null)
        {
            return 0;
        }
        TrialManager manager = TrialManager.get(server);
        int swept = 0;
        for (Map.Entry<UUID, Integer> entry : manager.cellsSnapshot().entrySet())
        {
            if (manager.sessionOf(entry.getKey()) != null)
            {
                continue;
            }
            int cell = entry.getValue();
            int reach = manager.reachOf(entry.getKey());
            if (cell != LOBBY_CELL)
            {
                BlockPos[] bounds = cellBounds(cell, reach);
                setRegionForced(arena, bounds[0], bounds[1], true);
                clearCellBlocks(arena, bounds[0], bounds[1]);
                PENDING_RELEASES.add(new PendingRelease(cell, reach, entry.getKey()));
            }
            swept++;
        }
        if (swept > 0)
        {
            LOGGER.info("[trial_portal] 启动清扫：回收 {} 个无主隔间（怪与强制加载下一轮收）", swept);
        }
        return swept;
    }

    /** {@link #sweepOrphanCells} 的第二步：区块这轮已经加载完，把遗留的怪清掉再松手。 */
    public static void tickPendingReleases(MinecraftServer server)
    {
        if (PENDING_RELEASES.isEmpty())
        {
            return;
        }
        ServerLevel arena = level(server);
        if (arena == null)
        {
            return; // 维度没加载就留到下一轮，不能先把记账销掉
        }
        TrialManager manager = TrialManager.get(server);
        List<PendingRelease> due = new ArrayList<>(PENDING_RELEASES);
        PENDING_RELEASES.clear();
        for (PendingRelease pending : due)
        {
            sweepCellMobs(arena, pending.cell(), pending.reach());
            BlockPos[] bounds = cellBounds(pending.cell(), pending.reach());
            setRegionForced(arena, bounds[0], bounds[1], false);
            manager.freeCell(pending.playerId());
            manager.takeHome(pending.playerId());
        }
    }

    /**
     * 把重启后还站在隔间区的人接回家：会话不进存档，所以他脚下那间已经被开服清扫拆掉了，
     * 不接这一把就是让他从空处掉进虚空。大厅里没会话地站着是正常状态，不算落难。
     */
    public static void rescueStrandedPlayers(MinecraftServer server)
    {
        TrialManager manager = TrialManager.get(server);
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers()); // 送回家会动原列表
        BlockPos lobby = cellOrigin(LOBBY_CELL);
        int safe = radius() + 2;
        for (ServerPlayer player : players)
        {
            if (!isInArena(player) || manager.sessionOf(player.getUUID()) != null)
            {
                continue;
            }
            if (Math.abs(player.getX() - (lobby.getX() + 0.5D)) <= safe
                    && Math.abs(player.getZ() - (lobby.getZ() + 0.5D)) <= safe)
            {
                continue;
            }
            LOGGER.warn("[trial_portal] {} 站在没有会话的隔间区（{}），先送回他的回返点",
                    player.getScoreboardName(), player.blockPosition().toShortString());
            returnHome(player);
        }
    }

    // ------------------------------------------------------------------ 建台

    /**
     * @param mode null = 大厅（摆三个模式终端 + 出口）；否则 = 隔间（摆该模式的终端 + 出口，
     *             玩家在里面要靠它继续下一波与结算离场）
     */
    public static void buildPlatform(ServerLevel level, BlockPos origin, @Nullable TrialMode mode)
    {
        forceChunks(level, origin);
        BlockState floor = platformBlock();
        int r = radius();
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dz = -r; dz <= r; dz++)
            {
                if (Math.max(Math.abs(dx), Math.abs(dz)) > r)
                {
                    continue;
                }
                BlockPos pos = origin.offset(dx, 0, dz);
                boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == r;
                if (level.getBlockState(pos).isAir())
                {
                    level.setBlock(pos, floor, Block.UPDATE_ALL);
                }
                // 围栏：怪能跳 1 格，所以按配置高度往上补齐。放在地板判断之外——大厅永远不拆，
                // 老存档上那圈只有一格高，得能在重复建台时长高。
                if (ring && !isRoomGate(mode, dx, dz, r))
                {
                    for (int dy = 1; dy <= fenceHeight(); dy++)
                    {
                        BlockPos wallPos = pos.above(dy);
                        if (level.getBlockState(wallPos).isAir())
                        {
                            level.setBlock(wallPos, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
        // 先清掉台面上任何遗留终端，再按槽位放：大厅永远不拆，旧布局留下的终端就会这样重复出现
        clearTerminals(level, origin, r);
        int gap = Math.max(1, (r - 1) / 3);
        int row = -(r - 1); // 北侧台沿一列：玩家落地时 yaw=180 正好对着它，也不挡中间的打斗区
        if (mode == null)
        {
            placeTerminal(level, origin.offset(-3 * gap, 1, row), ModBlocks.CHALLENGE_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(-gap, 1, row), ModBlocks.TOWER_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(gap, 1, row), ModBlocks.TRIAL_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(3 * gap, 1, row), ModBlocks.ARENA_EXIT.get().defaultBlockState());
        }
        else
        {
            placeTerminal(level, origin.offset(-gap, 1, row), blockFor(mode));
            placeTerminal(level, origin.offset(gap, 1, row), ModBlocks.ARENA_EXIT.get().defaultBlockState());
        }
    }

    /** 把平台范围内已经站着的终端（含上一版布局留下的）先抹平。 */
    private static void clearTerminals(ServerLevel level, BlockPos origin, int r)
    {
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dz = -r; dz <= r; dz++)
            {
                for (int dy = 1; dy <= 2; dy++)
                {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block instanceof TrialPortalBlock || block instanceof ArenaExitBlock)
                    {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /**
     * 试炼隔间要沿 +Z 往外建房，围栏得在南侧中间留 3 格宽的门（与走廊同宽），
     * 否则玩家清完一波要回平台终端继续时，被自己家的围栏挡在台面上。
     * 大厅与其他模式不开门：那里门外就是虚空，开门等于挖坑。
     */
    private static boolean isRoomGate(@Nullable TrialMode mode, int dx, int dz, int radius)
    {
        return mode == TrialMode.TRIAL && dz == radius && Math.abs(dx) <= 1;
    }

    private static void placeTerminal(ServerLevel level, BlockPos pos, BlockState state)
    {
        BlockState existing = level.getBlockState(pos);
        if (existing.isAir() || existing.getBlock() instanceof TrialPortalBlock)
        {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    /**
     * 记下"进场地之前人在哪"。直连玩法不经过大厅，所以这一步必须在两个开局入口都做，
     * 否则结算时没有回返点，人会被丢到世界出生点。
     */
    private static void recordReturnPoint(TrialManager manager, ServerPlayer player)
    {
        manager.recordHome(player.getUUID(), new TrialManager.HomePos(player.level().dimension(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    /**
     * 把这片隔间里遗留的怪清掉。生成的怪都 {@code setPersistenceRequired()}，不主动 discard 就永远留在存档里。
     * <p>
     * 调用时机决定它有没有用：实体查询只看已加载区块里、且已经注入完的怪。整场试炼期间这个隔间的区块一直是
     * 强制加载的，所以**离场拆台时**扫（{@link #releaseCellAt} 的 force 窗口里）才是主战场；开局前那一次只是兜底，
     * 只能清到"此刻恰好看得见"的怪——刚 {@code setChunkForced} 完的区块里，存档怪要再等一轮实体注入才进得来，
     * 所以开服清扫必须分两步（见 {@link #tickPendingReleases}）。
     *
     * @return 清掉的实体数
     */
    public static int sweepCellMobs(@Nullable ServerLevel arena, int cell, int reach)
    {
        if (arena == null || cell == LOBBY_CELL)
        {
            return 0;
        }
        AABB box = cellBox(cell, reach);
        int swept = 0;
        for (net.minecraft.world.entity.Mob mob : arena.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box))
        {
            mob.discard();
            swept++;
        }
        if (swept > 0)
        {
            LOGGER.info("[trial_portal] 隔间 {} 开局前清掉 {} 只遗留怪", cell, swept);
        }
        return swept;
    }

    /** 一个隔间从地板到最远进深的完整盒子：扫怪与清场都按它算，两处不许各写一份。 */
    public static AABB cellBox(int cell, int reach)
    {
        BlockPos origin = cellOrigin(cell);
        int half = cellHalfFootprint();
        int depth = half + Math.max(0, reach) + 8;
        return new AABB(origin.getX() - half, origin.getY() - 2, origin.getZ() - half,
                origin.getX() + half + 1, origin.getY() + 26, origin.getZ() + depth + 1);
    }

    /**
     * 一个 cell 的横向清场/强制加载半径。建台时 force 与拆台时 unforce 必须走同一个算法，
     * 两边各自换算一次就会差在区块边界上（真机就是这么漏下 chunk 的）。
     */
    private static int cellHalfFootprint()
    {
        return Math.max(radius() + 2, (RoomLibrary.maxWidth() + 3) / 2);
    }

    /** 平台会跨区块，先把覆盖范围强制加载，否则 setBlock 只能铺到已加载的部分。 */
    private static void forceChunks(ServerLevel level, BlockPos origin)
    {
        int half = cellHalfFootprint();
        setRegionForced(level, origin.offset(-half, 0, -half), origin.offset(half, 0, half), true);
    }

    /** 平台与走廊同材质，配置只读这一处。 */
    public static BlockState platformBlock()
    {
        ResourceLocation id = ResourceLocation.tryParse(TrialConfig.ARENA_PLATFORM_BLOCK.get());
        var block = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
        return (block == null ? Blocks.POLISHED_BLACKSTONE_BRICKS : block).defaultBlockState();
    }

    private static BlockState blockFor(TrialMode mode)
    {
        return switch (mode)
        {
            case CHALLENGE -> ModBlocks.CHALLENGE_PORTAL.get().defaultBlockState();
            case TOWER -> ModBlocks.TOWER_PORTAL.get().defaultBlockState();
            case TRIAL -> ModBlocks.TRIAL_PORTAL.get().defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ 传送

    /**
     * 落点做安全搜索：脚下实心、头顶至少两格空气。直接按坐标硬塞会出两种事故——
     * 卡进方块里，或者平台还没铺到那一层就掉进虚空。
     */
    private static void teleportSafe(ServerLevel target, ServerPlayer player, BlockPos desired, float yaw, float pitch)
    {
        BlockPos landing = findSafeLanding(target, desired);
        player.teleportTo(target, landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D,
                EnumSet.noneOf(RelativeMovement.class), yaw, pitch);
    }

    public static BlockPos findSafeLanding(ServerLevel level, BlockPos desired)
    {
        if (isSafeSpot(level, desired))
        {
            return desired;
        }
        for (int dy = 1; dy <= 4; dy++)
        {
            if (isSafeSpot(level, desired.above(dy)))
            {
                return desired.above(dy);
            }
        }
        for (int dy = 1; dy <= 4; dy++)
        {
            if (isSafeSpot(level, desired.below(dy)))
            {
                return desired.below(dy);
            }
        }
        int r = radius();
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dz = -r; dz <= r; dz++)
            {
                BlockPos candidate = new BlockPos(desired.getX() + dx, desired.getY(), desired.getZ() + dz);
                if (isSafeSpot(level, candidate))
                {
                    return candidate;
                }
            }
        }
        LOGGER.warn("[trial_portal] {} 附近找不到安全落点，按原坐标传送", desired.toShortString());
        return desired;
    }

    private static boolean isSafeSpot(ServerLevel level, BlockPos pos)
    {
        return level.getBlockState(pos.below()).isRedstoneConductor(level, pos.below())
                && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }

    // ------------------------------------------------------------------ 查询

    public static int cellOf(MinecraftServer server, UUID playerId)
    {
        return TrialManager.get(server).cellOf(playerId);
    }

    public static int usedCellCount(MinecraftServer server)
    {
        return TrialManager.get(server).cellsSnapshot().size();
    }
}
