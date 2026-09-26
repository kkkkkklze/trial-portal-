package com.trialportal.arena;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link RoomDefinition} 把一间房落到场地里：地板、外墙（留门洞）、可选立柱与遮挡物。
 * <p>
 * 全部 {@code setBlock}，不用原版 structure 模板：形状可 diff、可被 checkTrialData 校验，
 * 也不需要往仓库里塞二进制 .nbt。代价是外观上限低——这是刻意选的取舍。
 */
public final class RoomBuilder
{
    /** 门洞半宽：3 格宽的门，玩家不容易卡住。 */
    private static final int DOOR_HALF_WIDTH = 1;
    private static final int DOOR_HEIGHT = 3;

    private RoomBuilder() {}

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    public record Built(RoomDefinition room, BlockPos origin, List<BlockPos> spawnPoints, AABB region) {}

    /** @return 建成后这间房占的矩形区域（含外墙），用于范围判定与走廊对接 */
    public static Built build(ServerLevel level, RoomDefinition room, BlockPos origin, RandomSource random)
    {
        int w = room.width();
        int h = room.height();
        int d = room.depth();
        BlockState floor = blockState(room.floor, Blocks.POLISHED_BLACKSTONE_BRICKS);
        BlockState wall = blockState(room.wall, Blocks.BLACKSTONE);

        forceChunks(level, origin, w, d);
        // 地板：与 ArenaService 同一条约定——origin.y 这一层就是地板，玩家站 origin.y+1。
        for (int dx = 0; dx <= w + 1; dx++)
        {
            for (int dz = 0; dz <= d + 1; dz++)
            {
                level.setBlock(origin.offset(dx - 1, 0, dz - 1), floor, Block.UPDATE_ALL);
            }
        }
        // 内部清空 + 外墙（dy 从 1 起是可行走层，到 h 为止）
        for (int dx = -1; dx <= w; dx++)
        {
            for (int dz = -1; dz <= d; dz++)
            {
                boolean perimeter = dx == -1 || dz == -1 || dx == w || dz == d;
                for (int dy = 1; dy <= h; dy++)
                {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (perimeter)
                    {
                        if (isDoorway(dx, dz, w, dy))
                        {
                            continue; // 面向上一间/走廊的那侧留门洞，玩家走过来才进得去
                        }
                        level.setBlock(pos, wall, Block.UPDATE_ALL);
                    }
                    else if (!level.getBlockState(pos).isAir())
                    {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                if (room.ceiling)
                {
                    level.setBlock(origin.offset(dx, h + 1, dz), wall, Block.UPDATE_ALL);
                }
            }
        }
        // 立柱：沿中轴线左右对称
        BlockState pillar = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        for (int i = 0; i < room.pillars; i++)
        {
            int dz = 2 + i * 3;
            if (dz >= d - 1)
            {
                break;
            }
            for (int dy = 1; dy <= Math.min(h, 4); dy++)
            {
                level.setBlock(origin.offset(2, dy, dz), pillar, Block.UPDATE_ALL);
                level.setBlock(origin.offset(w - 3, dy, dz), pillar, Block.UPDATE_ALL);
            }
        }
        List<BlockPos> spawnPoints = room.spawnPointsAt(origin);
        // 遮挡物：随机撒在室内，但避开站怪点，否则怪会卡进墙里
        for (RoomDefinition.CoverEntry entry : room.cover)
        {
            BlockState cover = blockState(entry.block, Blocks.POLISHED_BLACKSTONE_WALL);
            for (int i = 0; i < entry.count; i++)
            {
                BlockPos pos = origin.offset(1 + random.nextInt(Math.max(1, w - 2)), 1,
                        1 + random.nextInt(Math.max(1, d - 2)));
                if (containsBlock(spawnPoints, pos))
                {
                    continue;
                }
                level.setBlock(pos, cover, Block.UPDATE_ALL);
            }
        }
        AABB region = new AABB(origin.getX() - 1, origin.getY(), origin.getZ() - 1,
                origin.getX() + w + 1, origin.getY() + h + 2, origin.getZ() + d + 1);
        return new Built(room, origin, spawnPoints, region);
    }

    /** 两间房之间的走廊：3 格宽地板 + 两侧墙（y 层与平台同高），玩家能从上一间走进下一间。 */
    /** 走廊与大厅/隔间平台同材质——材质只读 {@link ArenaService#platformBlock()} 这一处。 */
    public static BlockState entryFloor()
    {
        return ArenaService.platformBlock();
    }

    public static BlockState entryWall()
    {
        return Blocks.BLACKSTONE.defaultBlockState();
    }

    public static void buildCorridor(ServerLevel level, int centerX, int zFrom, int zTo,
                                     BlockState floor, BlockState wall)
    {
        int z0 = Math.min(zFrom, zTo) - 1;
        int z1 = Math.max(zFrom, zTo) + 1;
        forceRange(level, centerX - DOOR_HALF_WIDTH - 1, centerX + DOOR_HALF_WIDTH + 1, z0, z1);
        for (int dz = z0; dz <= z1; dz++)
        {
            for (int dx = -DOOR_HALF_WIDTH - 1; dx <= DOOR_HALF_WIDTH + 1; dx++)
            {
                boolean edge = Math.abs(dx) == DOOR_HALF_WIDTH + 1;
                BlockPos pos = new BlockPos(centerX + dx, ArenaService.FLOOR_Y, dz);
                if (!edge)
                {
                    level.setBlock(pos, floor, Block.UPDATE_ALL);
                }
                for (int dy = 1; dy <= DOOR_HEIGHT; dy++)
                {
                    BlockPos wallPos = pos.above(dy);
                    if (edge)
                    {
                        level.setBlock(wallPos, wall, Block.UPDATE_ALL);
                    }
                    else if (!level.getBlockState(wallPos).isAir())
                    {
                        level.setBlock(wallPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static boolean containsBlock(List<BlockPos> points, BlockPos pos)
    {
        for (BlockPos point : points)
        {
            if (point.getX() == pos.getX() && point.getZ() == pos.getZ())
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isDoorway(int dx, int dz, int w, int dy)
    {
        boolean nearEdge = dz == -1; // 朝向玩家走来的那一侧
        boolean middleX = Math.abs(dx - w / 2) <= DOOR_HALF_WIDTH;
        // dy 从 1 起才是可行走层，门洞要占满脚下一步往上的三格
        boolean lowEnough = dy <= DOOR_HEIGHT;
        return nearEdge && middleX && lowEnough;
    }

    private static BlockState blockState(String id, Block fallback)
    {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Block block = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
        if (block == null)
        {
            LOGGER.warn("[trial_portal] 方块 {} 不存在，回退到 {}", id, ForgeRegistries.BLOCKS.getKey(fallback));
            block = fallback;
        }
        return block.defaultBlockState();
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, int w, int d)
    {
        forceRange(level, origin.getX() - 1, origin.getX() + w, origin.getZ() - 1, origin.getZ() + d);
    }

    /** 只 force 真正会写到方块的那些区块：多 force 的区块拆场时还不回去，会在虚空里永久挂在内存里。 */
    private static void forceRange(ServerLevel level, int xFrom, int xTo, int zFrom, int zTo)
    {
        for (int cx = xFrom >> 4; cx <= xTo >> 4; cx++)
        {
            for (int cz = zFrom >> 4; cz <= zTo >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }
}
