package com.trialportal.arena;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 房间库：加载、按权重规划一条"若干普通房 + 最后一间 BOSS 房"的序列。
 * <p>
 * 抽样形状抄 RepurposedStructures 的 {@code requiredPieces / maximumPieceCounts}：
 * 同一间房在一场里默认最多用一次，池子不够才允许重复（并打一条 warn）。
 */
public final class RoomLibrary
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static volatile Map<String, RoomDefinition> byId = new TreeMap<>();

    private RoomLibrary() {}

    public static void replace(Map<String, RoomDefinition> loaded)
    {
        byId = new TreeMap<>(loaded);
    }

    @Nullable
    public static RoomDefinition get(String id)
    {
        return byId.get(id);
    }

    public static List<String> ids()
    {
        return new ArrayList<>(byId.keySet());
    }

    public static Map<String, RoomDefinition> snapshot()
    {
        return new LinkedHashMap<>(byId);
    }

    public static boolean exists(String id)
    {
        return id != null && byId.containsKey(id);
    }

    public static List<RoomDefinition> normalRooms()
    {
        List<RoomDefinition> result = new ArrayList<>();
        for (RoomDefinition room : byId.values())
        {
            if (!room.bossRoom)
            {
                result.add(room);
            }
        }
        return result;
    }

    public static List<RoomDefinition> bossRooms()
    {
        List<RoomDefinition> result = new ArrayList<>();
        for (RoomDefinition room : byId.values())
        {
            if (room.bossRoom)
            {
                result.add(room);
            }
        }
        return result;
    }

    /** 库里最宽的一间房，没有房时返回 null。 */
    @Nullable
    public static RoomDefinition widestRoom()
    {
        RoomDefinition widest = null;
        for (RoomDefinition room : byId.values())
        {
            if (widest == null || room.width() > widest.width())
            {
                widest = room;
            }
        }
        return widest;
    }

    /**
     * 库里最宽的一间房。拆隔间时横向清场范围要按它算——房间可以比平台半径还宽，
     * 只按平台半径清会把两侧外墙留在虚空里。
     */
    public static int maxWidth()
    {
        RoomDefinition widest = widestRoom();
        return widest == null ? 0 : widest.width();
    }

    /** 库里最高的一间房（含封顶那一层），同样服务于清场盒子。 */
    public static int maxHeight()
    {
        int tallest = 0;
        for (RoomDefinition room : byId.values())
        {
            tallest = Math.max(tallest, room.height() + (room.ceiling ? 1 : 0));
        }
        return tallest;
    }

    /**
     * 规划 roomCount 间普通房 + 1 间 BOSS 房（放最后）。
     *
     * @return 房间 ID 序列；null 表示池子空到规划不出来（调用方要给出可见的失败）
     */
    @Nullable
    public static List<String> plan(int roomCount, RandomSource random)
    {
        RoomDefinition boss = pick(bossRooms(), random);
        if (boss == null)
        {
            return null;
        }
        List<String> sequence = new ArrayList<>();
        List<RoomDefinition> pool = normalRooms();
        if (pool.isEmpty())
        {
            return null;
        }
        List<RoomDefinition> remaining = new ArrayList<>(pool);
        for (int i = 0; i < roomCount; i++)
        {
            if (remaining.isEmpty())
            {
                remaining.addAll(pool);
            }
            RoomDefinition picked = pick(remaining, random);
            if (picked == null)
            {
                return null;
            }
            remaining.remove(picked);
            sequence.add(picked.id());
        }
        if (roomCount > pool.size())
        {
            // 池子小于需求时会重复用房，这是有意的，但要让人知道
            LOGGER.info(
                    "[trial_portal] 需要 {} 间房，普通房只有 {} 间，本场会重复使用房间", roomCount, pool.size());
        }
        sequence.add(boss.id());
        return sequence;
    }

    @Nullable
    private static RoomDefinition pick(List<RoomDefinition> candidates, RandomSource random)
    {
        double total = 0.0D;
        for (RoomDefinition room : candidates)
        {
            total += Math.max(0.0D, room.weight);
        }
        if (total <= 0.0D)
        {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
        double roll = random.nextDouble() * total;
        for (RoomDefinition room : candidates)
        {
            roll -= Math.max(0.0D, room.weight);
            if (roll <= 0.0D)
            {
                return room;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
