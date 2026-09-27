package com.trialportal.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * 建场地的"笔刷"：每落一笔都记下真实写过的范围。
 * <p>
 * 存在的理由：拆场盒子以前按房间表里声明的 {@code size} 反推，那只要有人写了一格超出声明尺寸的东西
 * （灯座挑檐、庙的台阶、走廊的壁柱）就会留在虚空里，并且把它所在的那几个区块永久留在强制加载状态——
 * 正是这一轮刚修过的两个坑。改成"写过的一律记下来"，装饰作者就不必记得同步另一处尺寸。
 */
public final class Brush
{
    private final ServerLevel level;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private boolean empty = true;

    public Brush(ServerLevel level)
    {
        this.level = level;
    }

    /** 写一个方块并登记进足迹（挖成空气也算写过：拆场要连它一起还原）。 */
    public void put(BlockPos pos, BlockState state)
    {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        mark(pos);
    }

    public void put(int x, int y, int z, BlockState state)
    {
        put(new BlockPos(x, y, z), state);
    }

    /** 只登记"这块地被动过"，不写方块。 */
    public void mark(BlockPos pos)
    {
        mark(pos.getX(), pos.getY(), pos.getZ());
    }

    public void mark(int x, int y, int z)
    {
        if (empty)
        {
            minX = maxX = x;
            minY = maxY = y;
            minZ = maxZ = z;
            empty = false;
            return;
        }
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
    }

    public void union(@Nullable AABB other)
    {
        if (other == null)
        {
            return;
        }
        mark((int) Math.floor(other.minX), (int) Math.floor(other.minY), (int) Math.floor(other.minZ));
        mark((int) Math.ceil(other.maxX) - 1, (int) Math.ceil(other.maxY) - 1, (int) Math.ceil(other.maxZ) - 1);
    }

    /** 笔刷走过的矩形（含边界那一格）。没落过笔返回 null，调用点回退到按声明尺寸算的盒子。 */
    @Nullable
    public AABB region()
    {
        return empty ? null : new AABB(minX, minY, minZ, maxX + 1D, maxY + 1D, maxZ + 1D);
    }
}
