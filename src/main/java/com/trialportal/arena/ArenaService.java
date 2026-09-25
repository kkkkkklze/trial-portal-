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
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 试炼场地维度：大厅 + 每人一个隔间。
 * <p>
 * 平台全部由 {@code setBlock} 程序化生成，不用原版 structure 模板（1.20.1 的模板要二进制 .nbt 资产，
 * 既不可 diff 也进不了 checkTrialData 的校验）。因此生成必须幂等：重复进入同一个 cell 不会越铺越厚。
 */
public final class ArenaService
{
    public static final ResourceKey<Level> ARENA =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(TrialPortal.MODID, "arena"));

    /** 平台所在层；玩家站的位置是这一层 +1。 */
    public static final int FLOOR_Y = 64;
    /** cell 0 永远是大厅。 */
    public static final int LOBBY_CELL = 0;

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Integer> CELLS = new HashMap<>();
    private static final Map<UUID, Home> HOMES = new HashMap<>();
    private static final Set<Integer> USED_CELLS = new HashSet<>();
    private static int nextCell = 1;

    private ArenaService() {}

    public record Home(ResourceKey<Level> dimension, BlockPos pos, float yaw, float pitch) {}

    @Nullable
    public static ServerLevel level(MinecraftServer server)
    {
        return server.getLevel(ARENA);
    }

    public static boolean isArena(@Nullable ResourceKey<Level> key)
    {
        return ARENA.equals(key);
    }

    public static int radius()
    {
        return Math.max(1, TrialConfig.ARENA_PLATFORM_RADIUS.get());
    }

    public static BlockPos cellOrigin(int cell)
    {
        int spacing = Math.max(radius() * 2 + 8, TrialConfig.ARENA_CELL_SPACING.get());
        return new BlockPos((int) ((long) cell * spacing), FLOOR_Y, 0);
    }

    // ------------------------------------------------------------------ 进出

    /** 野外右键传送门 = 进大厅。记下回返点，之后结算/死亡/放弃都回这里。 */
    public static boolean enterLobby(ServerPlayer player)
    {
        ServerLevel arena = level(player.server);
        if (arena == null)
        {
            player.displayClientMessage(net.minecraft.network.chat.Component
                    .literal("试炼场地维度没有加载（数据包被禁用或 dimension/arena.json 缺失）"), false);
            LOGGER.warn("[trial_portal] 进大厅失败：找不到维度 {}", ARENA.location());
            return false;
        }
        UUID uuid = player.getUUID();
        TrialSession session = TrialManager.get(player.server).sessionOf(uuid);
        HOMES.putIfAbsent(uuid, new Home(player.level().dimension(), player.blockPosition(), player.getYRot(), player.getXRot()));
        int cell = session == null ? LOBBY_CELL : session.cell;
        BlockPos origin = cellOrigin(cell);
        buildPlatform(arena, origin, null);
        teleport(arena, player, origin);
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
        UUID uuid = player.getUUID();
        int cell = CELLS.computeIfAbsent(uuid, key -> {
            int candidate = nextCell++;
            USED_CELLS.add(candidate);
            return candidate;
        });
        USED_CELLS.add(cell);
        BlockPos origin = cellOrigin(cell);
        buildPlatform(arena, origin, definition.mode);
        TrialManager manager = TrialManager.get(player.server);
        if (!manager.start(player, definition, origin, cell))
        {
            TrialFeedback.failure(player, "开局失败：见服务端日志（trial_portal）");
            return false;
        }
        // 开局之后必须把玩家送进隔间：判定中心已经跟着 origin 走了，人还留在大厅会在下一 tick 被判离开范围
        // 传送时 yaw=180 正对北侧的隔间终端（origin.offset(0,1,-3)）
        teleport(arena, player, origin);
        return true;
    }

    /** 结算 / 放弃 / 中止后回野外；隔间按配置拆掉。 */
    public static void returnHome(ServerPlayer player)
    {
        UUID uuid = player.getUUID();
        Home home = HOMES.remove(uuid);
        ServerLevel arena = player.server.getLevel(ARENA);
        // 先把人送走再拆台：反过来会让玩家站在被抽掉平台的虚空里掉死
        ServerLevel target = home == null ? null : player.server.getLevel(home.dimension());
        if (target == null)
        {
            target = player.server.overworld();
        }
        BlockPos pos = home == null ? target.getSharedSpawnPos() : home.pos();
        float yaw = home == null ? 0.0F : home.yaw();
        float pitch = home == null ? 0.0F : home.pitch();
        player.teleportTo(target, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                EnumSet.noneOf(RelativeMovement.class), yaw, pitch);
        LOGGER.info("[trial_portal] 送回 {} @ {} {}", home == null ? "世界出生点（没有回返记录）" : home.dimension().location(),
                pos.toShortString(), player.getScoreboardName());
        releaseCell(arena, uuid);
    }

    /** 拆掉这个玩家占的隔间（大厅永不拆）。cell 记录会被清掉。 */
    public static void releaseCell(@Nullable ServerLevel arena, UUID uuid)
    {
        Integer cell = CELLS.remove(uuid);
        if (cell == null || cell == LOBBY_CELL)
        {
            return;
        }
        USED_CELLS.remove(cell);
        if (arena == null || !Boolean.TRUE.equals(TrialConfig.ARENA_CLEAR_CELL_ON_END.get()))
        {
            return;
        }
        releaseCellAt(arena, cell);
    }

    /** 拆掉指定编号隔间的平台并释放被强制加载的区块（大厅编号 0 永不拆）。 */
    public static void releaseCellAt(ServerLevel arena, int cell)
    {
        if (cell == LOBBY_CELL)
        {
            return;
        }
        clearPlatform(arena, cellOrigin(cell));
    }

    private static void teleport(ServerLevel arena, ServerPlayer player, BlockPos origin)
    {
        player.teleportTo(arena, origin.getX() + 0.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D,
                EnumSet.noneOf(RelativeMovement.class), 180.0F, 0.0F);
    }

    // ------------------------------------------------------------------ 建场

    /**
     * @param mode null = 大厅（摆三个模式终端 + 出口）；否则 = 隔间（摆该模式的终端 + 出口，
     *             玩家在里面要靠它继续下一波与结算离场）
     */
    public static void buildPlatform(ServerLevel level, BlockPos origin, TrialMode mode)
    {
        forceChunks(level, origin);
        BlockState floor = platformBlock();
        int r = radius();
        for (int dx = -r; dx <= r; dx++)
        {
            for (int dz = -r; dz <= r; dz++)
            {
                BlockPos pos = origin.offset(dx, 0, dz);
                if (!level.getBlockState(pos).isAir())
                {
                    continue; // 幂等：已经铺过就不动它
                }
                level.setBlock(pos, floor, Block.UPDATE_ALL);
                BlockPos above = pos.above();
                if (level.getBlockState(above).isAir() && Math.max(Math.abs(dx), Math.abs(dz)) == r)
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

    private static void clearPlatform(ServerLevel level, BlockPos origin)
    {
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
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        unforceChunks(level, origin);
    }

    private static void placeTerminal(ServerLevel level, BlockPos pos, BlockState state)
    {
        if (level.getBlockState(pos).isAir() || level.getBlockState(pos).getBlock() instanceof TrialPortalBlock)
        {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    /** 平台会跨区块，先把这三乘三格区块强制加载，否则 setBlock 只能铺到已加载的部分。 */
    private static void forceChunks(ServerLevel level, BlockPos origin)
    {
        int span = (radius() >> 4) + 1;
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;
        for (int dx = -span; dx <= span; dx++)
        {
            for (int dz = -span; dz <= span; dz++)
            {
                level.setChunkForced(centerChunkX + dx, centerChunkZ + dz, true);
            }
        }
    }

    private static void unforceChunks(ServerLevel level, BlockPos origin)
    {
        int span = (radius() >> 4) + 1;
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;
        for (int dx = -span; dx <= span; dx++)
        {
            for (int dz = -span; dz <= span; dz++)
            {
                level.setChunkForced(centerChunkX + dx, centerChunkZ + dz, false);
            }
        }
    }

    private static BlockState platformBlock()
    {
        ResourceLocation id = ResourceLocation.tryParse(TrialConfig.ARENA_PLATFORM_BLOCK.get());
        var block = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
        return (block == null ? Blocks.POLISHED_BLACKSTONE_BRICKS : block).defaultBlockState();
    }

    // ------------------------------------------------------------------ 查询

    private static BlockState blockFor(TrialMode mode)
    {
        return switch (mode)
        {
            case CHALLENGE -> ModBlocks.CHALLENGE_PORTAL.get().defaultBlockState();
            case TOWER -> ModBlocks.TOWER_PORTAL.get().defaultBlockState();
            case TRIAL -> ModBlocks.TRIAL_PORTAL.get().defaultBlockState();
        };
    }

    public static boolean isInArena(ServerPlayer player)
    {
        return isArena(player.level().dimension());
    }

    public static int cellOf(UUID uuid)
    {
        return CELLS.getOrDefault(uuid, LOBBY_CELL);
    }

    public static int usedCellCount()
    {
        return CELLS.size();
    }

    /** 大厅里那个模式该开哪份试炼：沿用「绑定优先，否则按 ID 字典序」。 */
    @Nullable
    public static TrialDefinition resolveFor(TrialMode mode)
    {
        return TrialLibrary.resolve(mode, null);
    }

    public static Collection<Integer> activeCells()
    {
        return USED_CELLS;
    }
}
