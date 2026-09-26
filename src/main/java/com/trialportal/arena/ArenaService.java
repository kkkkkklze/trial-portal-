package com.trialportal.arena;

import com.mojang.logging.LogUtils;
import com.trialportal.TrialPortal;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
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
        int cell = manager.allocateCell(player.getUUID());
        BlockPos origin = cellOrigin(cell);
        buildPlatform(arena, origin, definition.mode);
        if (!manager.start(player, definition, origin, cell))
        {
            manager.freeCell(player.getUUID());
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
        manager.freeCell(playerId);
        if (arena == null || cell == LOBBY_CELL || !Boolean.TRUE.equals(TrialConfig.ARENA_CLEAR_CELL_ON_END.get()))
        {
            return;
        }
        releaseCellAt(arena, cell, reach);
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
        BlockPos origin = cellOrigin(cell);
        // 清场盒子要按房间里最大的那间算：房可以比平台宽、比默认高度高，走廊还往 +Z 接出去 reach 格。
        // 只按平台半径清会把房间两侧外墙留在虚空里，并且把 RoomBuilder 强制加载的区块永久留在场上。
        int halfWidth = cellHalfFootprint();
        int topY = Math.max(10, RoomLibrary.maxHeight() + 2);
        int depth = halfWidth + Math.max(0, reach) + 8;
        BlockPos from = origin.offset(-halfWidth, -1, -halfWidth);
        BlockPos to = origin.offset(halfWidth, topY, depth);
        setRegionForced(arena, from, to, true);
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
        {
            if (arena.getBlockState(pos).isAir())
            {
                continue;
            }
            arena.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        setRegionForced(arena, from, to, false);
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
     *
     * @return 回收掉的隔间数
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
            if (manager.sessionOf(entry.getKey()) == null)
            {
                releaseCellAt(arena, entry.getValue(), manager.reachOf(entry.getKey()));
                manager.freeCell(entry.getKey());
                manager.takeHome(entry.getKey());
                swept++;
            }
        }
        if (swept > 0)
        {
            LOGGER.info("[trial_portal] 启动清扫：回收 {} 个无主隔间", swept);
        }
        return swept;
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
                if (!level.getBlockState(pos).isAir())
                {
                    continue; // 幂等：已经铺过就不动它
                }
                level.setBlock(pos, floor, Block.UPDATE_ALL);
                BlockPos above = pos.above();
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r && level.getBlockState(above).isAir()
                        && !isRoomGate(mode, dx, dz, r))
                {
                    level.setBlock(above, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL); // 围栏，防止一脚踩空掉虚空
                }
            }
        }
        if (mode == null)
        {
            placeTerminal(level, origin.offset(-4, 1, 0), ModBlocks.CHALLENGE_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(0, 1, -4), ModBlocks.TOWER_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(4, 1, 0), ModBlocks.TRIAL_PORTAL.get().defaultBlockState());
            placeTerminal(level, origin.offset(0, 1, 4), ModBlocks.ARENA_EXIT.get().defaultBlockState());
        }
        else
        {
            placeTerminal(level, origin.offset(0, 1, -3), blockFor(mode));
            placeTerminal(level, origin.offset(0, 1, 3), ModBlocks.ARENA_EXIT.get().defaultBlockState());
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

    private static void clearPlatform(ServerLevel level, BlockPos origin)
    {
        int r = radius();
        AABB region = new AABB(origin.offset(-r, -2, -r), origin.offset(r + 1, 6, r + 1));
        for (Entity entity : level.getEntitiesOfClass(Entity.class, region))
        {
            if (!(entity instanceof ServerPlayer))
            {
                entity.discard(); // 拆台时别把留在里面的怪变成悬空实体
            }
        }
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dz = -r; dz <= r; dz++)
            {
                if (Math.max(Math.abs(dx), Math.abs(dz)) > r)
                {
                    continue;
                }
                BlockPos pos = origin.offset(dx, 0, dz);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
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

    @Nullable
    private static TrialMode modeOf(TrialSession session)
    {
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        return definition == null ? null : definition.mode;
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
