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
        List<BlockPos> ring = new ArrayList<>();
        int w = width();
        int d = depth();
        for (int dx = 1; dx < w - 1 && ring.size() < this.spawnPoints; dx++)
        {
            ring.add(new BlockPos(roomOrigin.getX() + dx, roomOrigin.getY() + 1, roomOrigin.getZ() + 1));
            ring.add(new BlockPos(roomOrigin.getX() + dx, roomOrigin.getY() + 1, roomOrigin.getZ() + d - 2));
        }
        for (int dz = 1; dz < d - 1 && ring.size() < this.spawnPoints; dz++)
        {
            ring.add(new BlockPos(roomOrigin.getX() + 1, roomOrigin.getY() + 1, roomOrigin.getZ() + dz));
            ring.add(new BlockPos(roomOrigin.getX() + w - 2, roomOrigin.getY() + 1, roomOrigin.getZ() + dz));
        }
        while (ring.size() < this.spawnPoints)
        {
            // 内圈不够用就向中心补，保证点数与声明一致
            int extra = ring.size();
            ring.add(new BlockPos(roomOrigin.getX() + 2 + extra % Math.max(1, w - 4), roomOrigin.getY() + 1,
                    roomOrigin.getZ() + 2 + (extra / 2) % Math.max(1, d - 4)));
        }
        return ring.subList(0, this.spawnPoints);
    }

    /** BOSS 房的中心站位。 */
    public BlockPos bossAnchor(BlockPos roomOrigin)
    {
        return roomOrigin.offset(width() / 2, 1, depth() / 2);
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
        int index = (int) Math.floorMod(seed, this.variants.size());
        return mergedPalette(this.variants.get(index));
    }

    private static String pick(@Nullable String override, String fallback)
    {
        return override != null && !override.isBlank() ? override : fallback;
    }
}
