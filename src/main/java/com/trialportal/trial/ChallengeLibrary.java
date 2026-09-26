package com.trialportal.trial;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 资源副本库：{@code challenges/*.json}。CHALLENGE 模式只认这里，不认 {@code trials/}——
 * 两处都能配同一玩法迟早对不上。
 */
public final class ChallengeLibrary
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static volatile Map<String, ChallengeDefinition> byId = new TreeMap<>();

    private ChallengeLibrary() {}

    public static void replace(Map<String, ChallengeDefinition> loaded)
    {
        byId = new TreeMap<>(loaded);
    }

    @Nullable
    public static ChallengeDefinition get(String id)
    {
        return byId.get(id);
    }

    public static List<String> ids()
    {
        return new ArrayList<>(byId.keySet());
    }

    public static Map<String, ChallengeDefinition> snapshot()
    {
        return new LinkedHashMap<>(byId);
    }

    public static boolean exists(String id)
    {
        return id != null && byId.containsKey(id);
    }

    /** 列表按 {@code order} 再按 ID 排，界面与指令回显共用同一顺序。 */
    public static List<ChallengeDefinition> ordered()
    {
        List<ChallengeDefinition> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparingInt((ChallengeDefinition definition) -> definition.order)
                .thenComparing(ChallengeDefinition::id));
        return list;
    }

    /** 没点名副本时的默认：排第一的那份。库是空的就返回 null（调用方必须给出可见的失败）。 */
    @Nullable
    public static ChallengeDefinition first()
    {
        List<ChallengeDefinition> list = ordered();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 校验难度键；非法返回 null 并写一条 warn（调用方给玩家反馈）。 */
    @Nullable
    public static String normalizeDifficulty(@Nullable String key)
    {
        if (key == null || key.isBlank())
        {
            return "normal";
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ChallengeDefinition.DIFFICULTY_KEYS.contains(normalized))
        {
            LOGGER.warn("[trial_portal] 难度 {} 不认识，只允许 {}", key, ChallengeDefinition.DIFFICULTY_KEYS);
            return null;
        }
        return normalized;
    }
}
