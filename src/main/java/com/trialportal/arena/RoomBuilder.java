package com.trialportal.arena;

import com.mojang.logging.LogUtils;
import com.trialportal.config.TrialConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /** @return 建成后这间房真实写过的矩形区域（含外墙），拆场与范围判定都按它算 */
    public static Built build(ServerLevel level, RoomDefinition room, BlockPos origin, RandomSource random,
                              @Nullable RoomDefinition.Palette palette)
    {
        int w = room.width();
        int h = room.height();
        int d = room.depth();
        RoomDefinition.Palette used = palette == null ? room.paletteFor(0L) : palette;
        BlockState floor = blockState(used.floor, Blocks.POLISHED_BLACKSTONE_BRICKS);
        BlockState wall = blockState(used.wall, Blocks.BLACKSTONE);
        BlockState ceiling = blank(used.ceilingBlock) ? wall : blockState(used.ceilingBlock, Blocks.BLACKSTONE);
        BlockState pillar = blank(used.pillarBlock)
                ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState()
                : blockState(used.pillarBlock, Blocks.CHISELED_POLISHED_BLACKSTONE);
        BlockState accent = blank(used.patternBlock) ? pillar : blockState(used.patternBlock, Blocks.BLACKSTONE);
        boolean checker = "checker".equals(used.floorPattern);
        boolean border = "border".equals(used.floorPattern);
        BlockState light = lightState(room, used);
        int spacing = lightSpacing(room, used);
        Brush brush = new Brush(level);

        forceChunks(level, origin, w, d);
        // 地板：与 ArenaService 同一条约定——origin.y 这一层就是地板，玩家站 origin.y+1。
        for (int dx = 0; dx <= w + 1; dx++)
        {
            for (int dz = 0; dz <= d + 1; dz++)
            {
                boolean edgeTile = dx == 0 || dz == 0 || dx == w + 1 || dz == d + 1;
                boolean patterned = (checker && (dx + dz) % 2 == 0) || (border && edgeTile);
                brush.put(origin.offset(dx - 1, 0, dz - 1), patterned ? accent : floor);
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
                            brush.mark(pos); // 门洞是"挖出来"的，也算动过这块地
                            continue;
                        }
                        // 视线那一层（dy=2）每隔 spacing 格嵌一盏灯：虚空里 ambient_light 只有 0.15，
                        // 没灯的房等于黑盒子。梯度靠间距做，不靠抬全局环境光。
                        boolean lamp = light != null && dy == 2 && spacing > 0 && (dx + dz) % spacing == 0;
                        brush.put(pos, lamp ? light : wall);
                    }
                    else if (!level.getBlockState(pos).isAir())
                    {
                        brush.put(pos, Blocks.AIR.defaultBlockState());
                    }
                }
                if (room.ceiling)
                {
                    brush.put(origin.offset(dx, h + 1, dz), ceiling);
                }
            }
        }
        // 立柱：沿中轴线左右对称，默认从地板顶到天花板（材质取自这一套外观）
        int pillarTop = room.pillarHeight > 0 ? Math.min(h, room.pillarHeight) : h;
        for (int i = 0; i < room.pillars; i++)
        {
            int dz = 2 + i * 3;
            if (dz >= d - 1)
            {
                break;
            }
            for (int dy = 1; dy <= pillarTop; dy++)
            {
                brush.put(origin.offset(2, dy, dz), pillar);
                brush.put(origin.offset(w - 3, dy, dz), pillar);
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
                brush.put(pos, cover);
            }
        }
        // 房内隔断：作者画的几何要赢过随机撒的遮挡物，所以放在遮挡物之后落笔。
        // 压住站怪点/中心舞台这类冲突在数据校验阶段就红了，这里不再"跳过那一格"——
        // 跳过会在隔断上留洞，作者配的东西和玩家看到的就不是一回事。
        for (RoomDefinition.Partition partition : room.partitions)
        {
            BlockState barrier = blockState(partition.block, Blocks.BLACKSTONE_WALL);
            int top = Math.min(partition.height, h);
            for (int[] cell : partition.cells())
            {
                for (int dy = 1; dy <= top; dy++)
                {
                    brush.put(origin.offset(cell[0], dy, cell[1]), barrier);
                }
            }
        }
        AABB region = brush.region();
        if (region == null)
        {
            region = new AABB(origin.getX() - 1, origin.getY(), origin.getZ() - 1,
                    origin.getX() + w + 1, origin.getY() + h + 2, origin.getZ() + d + 1);
        }
        forceRegion(level, region); // 装饰可能写到声明尺寸之外，那些区块也得整场留在加载状态，拆场才还得回去
        return new Built(room, origin, spawnPoints, region);
    }

    /** 走廊与大厅/隔间平台同材质——材质只读 {@link ArenaService#platformBlock()} 这一处。 */
    public static BlockState entryFloor()
    {
        return ArenaService.platformBlock();
    }

    public static BlockState entryWall()
    {
        return Blocks.BLACKSTONE.defaultBlockState();
    }

    /**
     * 把上一段的末端接到下一间房的门洞：沿 Z 接到拐行 → 沿 X 横移 → 沿 Z 笔直穿到门口。
     * {@code xFrom == xTo} 时中段不成立，折线退化成原来那条直走廊，落格与旧实现一致。
     * <p>
     * 分两遍落笔是有原因的：拐角处两段车道会互相穿过对方的挡墙位。先写墙再写车道就会被自己封死
     * （玩家走进走廊撞墙），所以先把全车道算成一个集合，铺完地板与净空，最后补墙时跳过落在车道里的格。
     *
     * @return 这条走廊真实写过的区域（拆场按它算）
     */
    public static AABB buildLink(ServerLevel level, int xFrom, int zFrom, int xTo, int zTo,
                                 BlockState floor, BlockState wall, @Nullable BlockState light, int spacing)
    {
        int z0 = Math.min(zFrom, zTo) - 1;
        int z1 = Math.max(zFrom, zTo) + 1;
        int x0 = Math.min(xFrom, xTo) - DOOR_HALF_WIDTH - 1;
        int x1 = Math.max(xFrom, xTo) + DOOR_HALF_WIDTH + 1;
        forceRange(level, x0, x1, z0, z1);
        Brush brush = new Brush(level);

        Set<BlockPos> lane = new LinkedHashSet<>();
        List<Rail> rails = new ArrayList<>();
        // 横移放在"越过上一间那道墙之后的第一行"：贴着墙行拐弯会把横移段的挡墙落进上一间的屋里。
        int bend = xFrom == xTo ? zFrom : zFrom + 1;
        appendRun(lane, rails, xFrom, z0, xFrom, bend);
        if (xFrom != xTo)
        {
            appendRun(lane, rails, xFrom, bend, xTo, bend);
        }
        appendRun(lane, rails, xTo, bend, xTo, z1);

        for (BlockPos cell : lane)
        {
            brush.put(cell, floor);
            for (int dy = 1; dy <= DOOR_HEIGHT; dy++)
            {
                BlockPos above = cell.above(dy);
                if (!level.getBlockState(above).isAir())
                {
                    brush.put(above, Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (Rail rail : rails)
        {
            if (lane.contains(rail.pos()))
            {
                continue;
            }
            for (int dy = 1; dy <= DOOR_HEIGHT; dy++)
            {
                // 灯沿"走的那个轴"排布，与旧直走廊的 dz % spacing 同一条规则
                boolean lamp = light != null && dy == 2 && spacing > 0 && rail.along() % spacing == 0;
                brush.put(rail.pos().above(dy), lamp ? light : wall);
            }
        }
        AABB region = brush.region();
        forceRegion(level, region);
        return region == null ? new AABB(x0, ArenaService.FLOOR_Y, z0, x1 + 1D,
                ArenaService.FLOOR_Y + 1D, z1 + 1D) : region;
    }

    /** 挡墙格 + 它在行走轴上的坐标（灯的排布按这个坐标取模）。 */
    private record Rail(BlockPos pos, int along) {}

    /** 走廊的一段：沿轴铺 3 格宽车道，两侧各一道挡墙。轴由两个端点自己决定（只可能沿 X 或沿 Z）。 */
    private static void appendRun(Set<BlockPos> lane, List<Rail> rails, int xFrom, int zFrom, int xTo, int zTo)
    {
        boolean alongX = xFrom != xTo;
        int span = alongX ? xTo - xFrom : zTo - zFrom;
        int steps = Math.abs(span);
        int unit = Integer.compare(span, 0);
        for (int i = 0; i <= steps; i++)
        {
            int along = (alongX ? xFrom : zFrom) + unit * i;
            int fixed = alongX ? zFrom : xFrom;
            for (int side = -DOOR_HALF_WIDTH; side <= DOOR_HALF_WIDTH; side++)
            {
                lane.add(alongX ? new BlockPos(along, ArenaService.FLOOR_Y, fixed + side)
                        : new BlockPos(fixed + side, ArenaService.FLOOR_Y, along));
            }
            int guard = DOOR_HALF_WIDTH + 1;
            rails.add(alongX ? new Rail(new BlockPos(along, ArenaService.FLOOR_Y, fixed - guard), along)
                    : new Rail(new BlockPos(fixed - guard, ArenaService.FLOOR_Y, along), along));
            rails.add(alongX ? new Rail(new BlockPos(along, ArenaService.FLOOR_Y, fixed + guard), along)
                    : new Rail(new BlockPos(fixed + guard, ArenaService.FLOOR_Y, along), along));
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

    /** 配置里的默认灯；写成空气或不存在的方块就是"不布灯"，不报错（这是给服务器管理员关灯的开关）。 */
    @Nullable
    public static BlockState defaultLight()
    {
        return lightState(null, null);
    }

    public static int defaultLightSpacing()
    {
        return lightSpacing(null, null);
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }

    /**
     * 这间房用什么灯：外观套 → 房间默认 → 配置 {@code arena.light_block}。返回 null = 不布灯。
     * 房间自己写了却不存在的方块不再静默回退——那等于"配了灯但房还是黑的"，直接不布灯并 warn。
     */
    @Nullable
    private static BlockState lightState(@Nullable RoomDefinition room, @Nullable RoomDefinition.Palette palette)
    {
        String configured = palette != null && !blank(palette.lightBlock) ? palette.lightBlock
                : (room != null && !blank(room.lightBlock) ? room.lightBlock : TrialConfig.ARENA_LIGHT_BLOCK.get());
        if (blank(configured))
        {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(configured);
        Block block = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
        if (block == null)
        {
            LOGGER.warn("[trial_portal] 灯方块 {} 不存在，这间隔间不布灯", configured);
            return null;
        }
        return block.defaultBlockState();
    }

    /** 灯的间隔：房间没写就用配置默认。0 = 不布灯。 */
    private static int lightSpacing(@Nullable RoomDefinition room, @Nullable RoomDefinition.Palette palette)
    {
        if (room != null && room.lightSpacing > 0)
        {
            return room.lightSpacing;
        }
        return Math.max(0, TrialConfig.ARENA_LIGHT_SPACING.get());
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

    /** 把真实写过的区域整块 force 住：拆场按同一个区域还回去，两边不许各算一次尺寸。 */
    private static void forceRegion(ServerLevel level, AABB region)
    {
        if (region == null)
        {
            return;
        }
        forceRange(level, (int) Math.floor(region.minX), (int) Math.ceil(region.maxX) - 1,
                (int) Math.floor(region.minZ), (int) Math.ceil(region.maxZ) - 1);
    }
}
