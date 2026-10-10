package com.trialportal.trial;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 守塔内容库：{@code defenses/*.json}。DEFENSE 模式只认这里，
 * 不去 {@code trials/} 也不去 {@code challenges/} 找——同一玩法两处可配迟早对不上。
 */
public final class DefenseLibrary
{
    private static volatile Map<String, DefenseDefinition> byId = new TreeMap<>();

    private DefenseLibrary() {}

    public static void replace(Map<String, DefenseDefinition> loaded)
    {
        byId = new TreeMap<>(loaded);
    }

    @Nullable
    public static DefenseDefinition get(String id)
    {
        return byId.get(id);
    }

    public static List<String> ids()
    {
        return new ArrayList<>(byId.keySet());
    }

    public static Map<String, DefenseDefinition> snapshot()
    {
        return new LinkedHashMap<>(byId);
    }

    public static boolean exists(String id)
    {
        return id != null && byId.containsKey(id);
    }

    /** 按 {@code order} 再按 ID 排，界面与指令回显共用同一顺序。 */
    public static List<DefenseDefinition> ordered()
    {
        List<DefenseDefinition> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparingInt((DefenseDefinition definition) -> definition.order)
                .thenComparing(DefenseDefinition::id));
        return list;
    }

    /** 没点名守塔定义时的默认：排第一的那份。库空就返回 null，调用方必须给出可见的失败。 */
    @Nullable
    public static DefenseDefinition first()
    {
        List<DefenseDefinition> list = ordered();
        return list.isEmpty() ? null : list.get(0);
    }
}
