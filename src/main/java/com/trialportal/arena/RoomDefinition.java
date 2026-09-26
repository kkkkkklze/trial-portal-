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

    /** 遮挡物：随机撒在室内的方块，用来挡视线与弹道。 */
    @SerializedName("cover")
    public List<CoverEntry> cover = new ArrayList<>();

    /** 柱子对数（沿中轴线对称），0 = 不立柱。 */
    @SerializedName("pillars")
    public int pillars;

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
}
