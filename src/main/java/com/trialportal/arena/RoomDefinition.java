package com.trialportal.arena;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一间房：只有几何与容量，不含怪物与奖励。
 * <p>
 * 刻意不写"这间房刷什么怪"——怪与奖励属于试炼定义（{@code trials/*.json} 的 waves），
 * 两处各写一份迟早会不一致。房间只回答"这间房能装多少、在哪儿站怪"。
 */
public class RoomDefinition
{
    @SerializedName("display_name")
    public String displayName;

    @SerializedName("weight")
    public double weight = 1.0D;

    /** [宽, 高, 深]，内部可用空间；外墙算在尺寸之外。 */
    @SerializedName("size")
    public List<Integer> size = new ArrayList<>();

    @SerializedName("floor")
    public String floor = "minecraft:polished_blackstone_bricks";

    @SerializedName("wall")
    public String wall = "minecraft:blackstone";

    /** 是否封顶。虚空里不封顶更看得出自己在哪，也省一次填充。 */
    @SerializedName("ceiling")
    public boolean ceiling;

    /** 灯方块 ID；空 = 用配置里的默认灯（{@code arena.light_block}）。 */
    @SerializedName("light_block")
    public String lightBlock = "";

    /** 灯沿墙环的间隔（格）；0 = 用配置里的默认间距。 */
    @SerializedName("light_spacing")
    public int lightSpacing;

    /** 遮挡物：随机撒在室内的方块，用来挡视线与弹道。 */
    @SerializedName("cover")
    public List<CoverEntry> cover = new ArrayList<>();

    /**
     * 外观变体：同一间房、同样的尺寸与站怪点，换一套材质。开局按会话种子挑一套，
     * 于是"同一间房第二次进来长得不一样"，而房间几何与刷怪完全不变（改的只是外观）。
     * 每个字段留空就沿用上面那份默认。
     */
    @SerializedName("variants")
    public List<Palette> variants = new ArrayList<>();

    /** 地板花纹：none / checker（隔格拼花）/ border（内圈一道边）。 */
    @SerializedName("floor_pattern")
    public String floorPattern = "none";

    /** 天花板材质；空 = 跟墙同材质。 */
    @SerializedName("ceiling_block")
    public String ceilingBlock = "";

    /**
     * 布局变体：同一间房换它在链条里的<b>落点</b>——离上一间多远、往旁边错开多少。
     * 外观变体换的是材质，这里换的是走动时的空间感（一排正对着推过去的房 vs 折来折去的桥）。
     * 第一间房永远用不上它们：大厅那一侧的门洞与围栏缺口是对着中轴线留的，横移会把玩家引进虚空。
     */
    @SerializedName("layouts")
    public List<Layout> layouts = new ArrayList<>();

    /**
     * 房内隔断：沿一条轴对齐的线段砌一道墙，把空盒子切成有遮挡、有拐角的空间。
     * 坐标是室内局部格（{@code dx}/{@code dz} 各自 1~尺寸-2），不许压住站怪点或 BOSS 站位，
     * 也不许把房内封出不通的小间——怪卡进墙里、玩家打不到那一格，波次就永远清不完。
     * 这些是<b>校验</b>而不是运行期悄悄跳过：跳过会在隔断上留洞，作者看到的东西和玩家看到的不一样。
     */
    @SerializedName("partitions")
    public List<Partition> partitions = new ArrayList<>();

    /** 柱子对数（沿中轴线对称），0 = 不立柱。 */
    @SerializedName("pillars")
    public int pillars;

    /** 立柱材质；空 = 雕纹黑石砖柱。 */
    @SerializedName("pillar_block")
    public String pillarBlock = "";

    /** 柱高（格）；0 = 从地板一直顶到天花板（以前写死 4，8 格高的厅里柱子就成了半截桩）。 */
    @SerializedName("pillar_height")
    public int pillarHeight;

    /** 站怪点数量。 */
    @SerializedName("spawn_points")
    public int spawnPoints = 6;

    /** 同时可容纳的怪数上限；一波怪多过这个数就换下一间房装不下的部分继续用生成点循环。 */
    @SerializedName("mob_cap")
    public int mobCap = 16;

    @SerializedName("boss_room")
    public boolean bossRoom;

    private transient String resolvedId;

    public String id()
    {
        return this.resolvedId == null ? "" : this.resolvedId;
    }

    public String displayNameOrDefault()
    {
        return this.displayName == null || this.displayName.isBlank() ? this.id() : this.displayName;
    }

    public int width()
    {
        return this.size.get(0);
    }

    public int height()
    {
        return this.size.get(1);
    }

    public int depth()
    {
        return this.size.get(2);
    }

    /**
     * @return null 表示通过；否则为失败原因
     */
    @Nullable
    public String resolve(String id)
    {
        if (size.size() != 3)
        {
            return "size 必须是 [宽, 高, 深] 三个整数";
        }
        int w = width();
        int h = height();
        int d = depth();
        if (w < 5 || d < 5 || w > 31 || d > 31)
        {
            return "室内宽深要在 5~31 之间（当前 " + w + "x" + d + "）";
        }
        if (h < 3 || h > 16)
        {
            return "室内高度要在 3~16 之间（当前 " + h + "）";
        }
        if (this.weight <= 0.0D)
        {
            return "weight 必须为正（当前 " + this.weight + "）";
        }
        if (this.spawnPoints < 1)
        {
            return "spawn_points 至少 1";
        }
        if (this.mobCap < this.spawnPoints)
        {
            return "mob_cap（" + this.mobCap + "）不该小于 spawn_points（" + this.spawnPoints + "），否则有生成点永远用不上";
        }
        if (this.spawnPoints > interiorCells(w, d))
        {
            return "spawn_points " + this.spawnPoints + " 超过室内可放点上限 " + interiorCells(w, d);
        }
        if (this.pillarBlock != null && !this.pillarBlock.isBlank()
                && ResourceLocation.tryParse(this.pillarBlock) == null)
        {
            return "pillar_block 不是合法方块 ID（" + this.pillarBlock + "）";
        }
        if (this.pillarHeight < 0 || this.pillarHeight > 16)
        {
            return "pillar_height 要在 0~16 之间（0 = 顶到天花板），当前 " + this.pillarHeight;
        }
        if (this.lightBlock != null && !this.lightBlock.isBlank()
                && ResourceLocation.tryParse(this.lightBlock) == null)
        {
            return "light_block 不是合法方块 ID（" + this.lightBlock + "）";
        }
        if (this.lightSpacing < 0 || this.lightSpacing > 32)
        {
            return "light_spacing 要在 0~32 之间（0 = 用配置默认），当前 " + this.lightSpacing;
        }
        String floorError = checkBlockId("floor", this.floor);
        if (floorError != null)
        {
            return floorError;
        }
        String wallError = checkBlockId("wall", this.wall);
        if (wallError != null)
        {
            return wallError;
        }
        for (CoverEntry entry : this.cover)
        {
            String coverError = checkBlockId("cover.block", entry.block);
            if (coverError != null)
            {
                return coverError;
            }
            if (entry.count < 0)
            {
                return "cover 里 " + entry.block + " 的 count 不能为负";
            }
        }
        String patternError = checkPattern("floor_pattern", this.floorPattern);
        if (patternError != null)
        {
            return patternError;
        }
        for (Palette variant : this.variants)
        {
            String variantError = variant.resolve(id);
            if (variantError != null)
            {
                return variantError;
            }
        }
        for (Layout layout : this.layouts)
        {
            String layoutError = layout.resolve(id);
            if (layoutError != null)
            {
                return layoutError;
            }
        }
        if (layoutsAreInert())
        {
            return "layouts 里每一套的 gap 与 lateral 都是 0：列了等于没列（检查是不是把键名拼错了）";
        }
        for (Partition partition : this.partitions)
        {
            String partitionError = partition.resolve(id, w, d, h);
            if (partitionError != null)
            {
                return partitionError;
            }
        }
        String clash = checkPartitionsClearOfStakes(id);
        if (clash != null)
        {
            return clash;
        }
        if (this.pillars < 0 || this.pillars * 4 >= Math.min(w, d))
        {
            return "pillars " + this.pillars + " 太多，房间 " + w + "x" + d + " 放不下";
        }
        this.resolvedId = id;
        if (this.displayName == null || this.displayName.isBlank())
        {
            this.displayName = id;
        }
        return null;
    }

    private static int interiorCells(int w, int d)
    {
        return Math.max(1, (w - 2) * (d - 2));
    }

    /** 隔断占用的室内格（含端点），供校验与建房共用同一份口径。 */
    public List<int[]> partitionCells()
    {
        List<int[]> cells = new ArrayList<>();
        for (Partition partition : this.partitions)
        {
            cells.addAll(partition.cells());
        }
        return cells;
    }

    /**
     * 隔断不许压住站怪点，也不许压住 BOSS 房的中心舞台。站怪点是确定的内圈排布，
     * 所以这件事在数据阶段就能判，不用等到运行期发现"怪卡进墙里"。
     */
    @Nullable
    private String checkPartitionsClearOfStakes(String id)
    {
        if (this.partitions.isEmpty())
        {
            return null;
        }
        List<int[]> cells = partitionCells();
        for (int[] point : localSpawnCells())
        {
            if (containsCell(cells, point[0], point[1]))
            {
                return "房间 " + id + " 的隔断压住了站怪点 (" + point[0] + "," + point[1]
                        + ")：怪会卡进墙里，玩家永远打不到它，波次清不完";
            }
        }
        if (this.bossRoom)
        {
            int[] anchor = localBossAnchor();
            if (containsCell(cells, anchor[0], anchor[1]))
            {
                return "房间 " + id + " 是 BOSS 房，隔断却压住了中心舞台 (" + anchor[0] + "," + anchor[1]
                        + ")：BOSS 会嵌在墙里";
            }
        }
        return null;
    }

    private static boolean containsCell(List<int[]> cells, int x, int z)
    {
        for (int[] cell : cells)
        {
            if (cell[0] == x && cell[1] == z)
            {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String checkBlockId(String field, String value)
    {
        if (value == null || value.isBlank())
        {
            return field + " 为空";
        }
        if (ResourceLocation.tryParse(value) == null)
        {
            return field + " 不是合法方块 ID（" + value + "）";
        }
        return null;
    }

    /** 花纹名字写错以前只会静默按 none 处理，这里直接红。 */
    @Nullable
    private static String checkPattern(String field, String value)
    {
        if (value == null || value.isBlank() || FLOOR_PATTERNS.contains(value))
        {
            return null;
        }
        return field + " 只认 " + String.join("/", FLOOR_PATTERNS) + "（当前 " + value + "）";
    }

    private static final List<String> FLOOR_PATTERNS = List.of("none", "checker", "border");

    /**
     * 室内站怪点：沿内圈顺时针均分，够不着就退回中心区。
     * 给定点数即可复现，不靠随机——这样"同一间房每次刷怪位置一样"，玩家能背板。
     */
    public List<BlockPos> spawnPointsAt(BlockPos roomOrigin)
    {
        List<BlockPos> points = new ArrayList<>();
        for (int[] cell : localSpawnCells())
        {
            points.add(roomOrigin.offset(cell[0], 1, cell[1]));
        }
        return points;
    }

    /**
     * 站怪点的<b>室内局部</b>坐标 [dx, dz]。校验必须走这一份而不是上面那份：
     * {@code BlockPos} 的类初始化会去拉 log4j，而离线门 {@code checkTrialData} 跑在没有日志实现的
     * 工具 JVM 里，一碰就炸（本机踩过的同一条）。所以"点怎么排"只在这里算一次，两边共用。
     */
    public List<int[]> localSpawnCells()
    {
        List<int[]> ring = new ArrayList<>();
        int w = width();
        int d = depth();
        for (int dx = 1; dx < w - 1 && ring.size() < this.spawnPoints; dx++)
        {
            ring.add(new int[] {dx, 1});
            ring.add(new int[] {dx, d - 2});
        }
        for (int dz = 1; dz < d - 1 && ring.size() < this.spawnPoints; dz++)
        {
            ring.add(new int[] {1, dz});
            ring.add(new int[] {w - 2, dz});
        }
        while (ring.size() < this.spawnPoints)
        {
            // 内圈不够用就向中心补，保证点数与声明一致
            int extra = ring.size();
            ring.add(new int[] {2 + extra % Math.max(1, w - 4), 2 + (extra / 2) % Math.max(1, d - 4)});
        }
        return ring.subList(0, Math.min(this.spawnPoints, ring.size()));
    }

    /** BOSS 房的中心站位。 */
    public BlockPos bossAnchor(BlockPos roomOrigin)
    {
        return roomOrigin.offset(width() / 2, 1, depth() / 2);
    }

    /** 中心舞台的室内局部坐标，校验用（理由同 {@link #localSpawnCells}）。 */
    public int[] localBossAnchor()
    {
        return new int[] {width() / 2, depth() / 2};
    }

    public static class CoverEntry
    {
        @SerializedName("block")
        public String block;

        @SerializedName("count")
        public int count = 1;
    }

    /**
     * 一套外观。当变体用时，留空的字段沿用房间默认；当解析结果用时（{@link #paletteFor}）所有字段都已定。
     */
    public static class Palette
    {
        @SerializedName("name")
        public String name;
        @SerializedName("floor")
        public String floor;
        @SerializedName("wall")
        public String wall;
        @SerializedName("ceiling_block")
        public String ceilingBlock;
        @SerializedName("pillar_block")
        public String pillarBlock;
        @SerializedName("light_block")
        public String lightBlock;
        @SerializedName("floor_pattern")
        public String floorPattern;
        /** 花纹的第二个材质（checker 的隔格、border 的那道边）；空 = 用柱子材质。 */
        @SerializedName("pattern_block")
        public String patternBlock;

        @Nullable
        String resolve(String roomId)
        {
            String where = "房间 " + roomId + " 的变体 " + (this.name == null ? "?" : this.name);
            for (String candidate : new String[] {this.floor, this.wall, this.ceilingBlock, this.pillarBlock,
                    this.lightBlock, this.patternBlock})
            {
                if (candidate != null && !candidate.isBlank() && ResourceLocation.tryParse(candidate) == null)
                {
                    return where + " 含非法方块 ID（" + candidate + "）";
                }
            }
            String patternError = checkPattern(where + " 的 floor_pattern", this.floorPattern);
            if (patternError != null)
            {
                return patternError;
            }
            if (this.floor != null && this.floor.isBlank() && this.wall != null && this.wall.isBlank()
                    && this.pillarBlock != null && this.pillarBlock.isBlank() && this.ceilingBlock != null
                    && this.ceilingBlock.isBlank() && this.lightBlock != null && this.lightBlock.isBlank()
                    && (this.floorPattern == null || this.floorPattern.isBlank() || "none".equals(this.floorPattern)))
            {
                return where + " 一个字段都没填，这套变体是空的（多半是键名拼错了）";
            }
            return null;
        }
    }

    /** 变体套在默认之上：谁写了听谁的。 */
    public Palette mergedPalette(@Nullable Palette variant)
    {
        Palette merged = new Palette();
        merged.floor = pick(variant == null ? null : variant.floor, floor);
        merged.wall = pick(variant == null ? null : variant.wall, wall);
        merged.ceilingBlock = pick(variant == null ? null : variant.ceilingBlock, ceilingBlock);
        merged.pillarBlock = pick(variant == null ? null : variant.pillarBlock, pillarBlock);
        merged.lightBlock = pick(variant == null ? null : variant.lightBlock, lightBlock);
        merged.floorPattern = pick(variant == null ? null : variant.floorPattern, floorPattern);
        merged.patternBlock = pick(variant == null ? null : variant.patternBlock,
                variant == null ? null : variant.pillarBlock);
        merged.name = variant == null ? null : variant.name;
        return merged;
    }

    /** 按种子挑一套外观：同一场会话里稳定（玩家能背板），换一场就可能不一样。 */
    public Palette paletteFor(long seed)
    {
        if (this.variants == null || this.variants.isEmpty())
        {
            return mergedPalette(null);
        }
        return mergedPalette(this.variants.get(indexFor(seed, PALETTE_SHIFT, this.variants.size())));
    }

    /**
     * 按种子挑一种落点。必须取种子的<b>另一段比特</b>：两条列表都取最低位、长度又一样时，
     * 外观和落点会永远配成固定那几对（"苔石厅"必定横移），四组合只出两组合，多样性直接减半。
     */
    public Layout layoutFor(long seed)
    {
        if (this.layouts == null || this.layouts.isEmpty())
        {
            return Layout.straight();
        }
        return this.layouts.get(indexFor(seed, LAYOUT_SHIFT, this.layouts.size()));
    }

    private static int indexFor(long seed, int shift, int size)
    {
        return (int) Math.floorMod(Math.floorDiv(seed, 1L << shift), size);
    }

    private static final int PALETTE_SHIFT = 0;
    private static final int LAYOUT_SHIFT = 1;

    private static String pick(@Nullable String override, String fallback)
    {
        return override != null && !override.isBlank() ? override : fallback;
    }

    /**
     * 一间房在链条里怎么落：与上一间隔多远（{@code gap}，中间是悬空的走廊）、往旁边错开几格
     * （{@code lateral}，走廊就地拐弯过去）。两者都是 0 就是老样子——正对着一间推过去。
     */
    public static class Layout
    {
        @SerializedName("name")
        public String name;

        /** 与上一间房之间的虚空宽度（格）。0 = 墙贴墙，和以前一样。 */
        @SerializedName("gap")
        public int gap;

        /** 整间房沿 X 轴横移（格），可负。运行时还会按隔间横向余量收紧，横不出去。 */
        @SerializedName("lateral")
        public int lateral;

        public static Layout straight()
        {
            Layout straight = new Layout();
            straight.name = "直行";
            return straight;
        }

        @Nullable
        String resolve(String roomId)
        {
            String where = "房间 " + roomId + " 的布局";
            if (this.gap < 0 || this.gap > MAX_GAP)
            {
                return where + " 的 gap 要在 0~" + MAX_GAP + " 之间（当前 " + this.gap + "）";
            }
            if (this.lateral < -MAX_LATERAL || this.lateral > MAX_LATERAL)
            {
                return where + " 的 lateral 要在 ±" + MAX_LATERAL + " 之间（当前 " + this.lateral + "）";
            }
            if (this.lateral != 0 && this.gap < MIN_GAP_FOR_JOG)
            {
                return where + " 横移 " + this.lateral + " 格却只留 " + this.gap
                        + " 格间隙：走廊的横移段会压在新房外墙底下，被墙封死。lateral 非 0 时 gap 至少 "
                        + MIN_GAP_FOR_JOG;
            }
            return null;
        }

        private static final int MAX_GAP = 16;
        private static final int MAX_LATERAL = 16;

        /** 横移必须留出的最小间隙：走廊的横移段要走在两间房之间，贴着墙行就会被新房外墙封死。 */
        public static final int MIN_GAP_FOR_JOG = 2;
    }

    /** 整份列表都是直行 = 一行没配（多半是把 lateral 拼错了），与其静默按老样子铺不如直接红。 */
    public boolean layoutsAreInert()
    {
        if (this.layouts == null || this.layouts.isEmpty())
        {
            return false;
        }
        for (Layout layout : this.layouts)
        {
            if (layout.gap != 0 || layout.lateral != 0)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 一道房内隔断：室内局部坐标上的一条轴对齐线段，沿线从地板往上砌 {@code height} 格。
     * <p>
     * {@code height} 为 1 时玩家与怪都跳得过去，只挡视线；2 格起就是真的拦路——所以"隔断会不会
     * 把房里封出不通的小间"必须由判据盯着，而不是靠作者自觉。
     */
    public static class Partition
    {
        @SerializedName("name")
        public String name;

        /** 线段起点 [dx, dz]（室内局部格）。 */
        @SerializedName("from")
        public List<Integer> from = new ArrayList<>();

        /** 线段终点 [dx, dz]，含端点。与起点同 dx 或同 dz。 */
        @SerializedName("to")
        public List<Integer> to = new ArrayList<>();

        @SerializedName("block")
        public String block = "minecraft:blackstone_wall";

        /** 隔断高度（格），1 起。 */
        @SerializedName("height")
        public int height = 1;

        /** 沿线段铺开的格（含端点）。斜线在这里会退化成"每步同时动两轴"的非法写法，校验已先拦掉。 */
        public List<int[]> cells()
        {
            List<int[]> cells = new ArrayList<>();
            if (this.from.size() != 2 || this.to.size() != 2)
            {
                return cells;
            }
            int x0 = this.from.get(0);
            int z0 = this.from.get(1);
            int x1 = this.to.get(0);
            int z1 = this.to.get(1);
            int dx = Integer.compare(x1, x0);
            int dz = Integer.compare(z1, z0);
            int steps = Math.abs(x1 - x0) + Math.abs(z1 - z0);
            for (int i = 0; i <= steps; i++)
            {
                cells.add(new int[] {x0 + dx * i, z0 + dz * i});
            }
            return cells;
        }

        @Nullable
        String resolve(String roomId, int w, int d, int h)
        {
            String where = "房间 " + roomId + " 的隔断 " + (this.name == null ? "?" : this.name);
            if (this.from.size() != 2 || this.to.size() != 2)
            {
                return where + " 的 from/to 必须是 [dx, dz] 两个整数";
            }
            int x0 = this.from.get(0);
            int z0 = this.from.get(1);
            int x1 = this.to.get(0);
            int z1 = this.to.get(1);
            if (x0 != x1 && z0 != z1)
            {
                return where + " 不是轴对齐的线段：(" + x0 + "," + z0 + ")→(" + x1 + "," + z1
                        + ")，要么同 dx 要么同 dz（斜线铺出来是断断续续的格子）";
            }
            if (x0 < 1 || x1 < 1 || z0 < 1 || z1 < 1 || x0 > w - 2 || x1 > w - 2 || z0 > d - 2 || z1 > d - 2)
            {
                return where + " 越出了室内（dx 要在 1~" + (w - 2) + "，dz 要在 1~" + (d - 2)
                        + "）：贴外墙写等于改外墙，门洞那一侧还会把玩家堵在门外";
            }
            String blockError = checkBlockId("partition.block", this.block);
            if (blockError != null)
            {
                return where + " 的 " + blockError;
            }
            if (this.height < 1 || this.height > h)
            {
                return where + " 的 height 要在 1~" + h + "（室内高）之间，当前 " + this.height;
            }
            return null;
        }
    }
}
