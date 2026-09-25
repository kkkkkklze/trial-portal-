package com.trialportal.trial;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 试炼定义的静态库。数据包重载时整体替换，运行期只读。
 */
public final class TrialLibrary
{
    private static volatile Map<String, TrialDefinition> byId = new TreeMap<>();

    private TrialLibrary() {}

    public static void replace(Map<String, TrialDefinition> loaded)
    {
        byId = new TreeMap<>(loaded);
    }

    @Nullable
    public static TrialDefinition get(String id)
    {
        return byId.get(id);
    }

    public static List<String> ids()
    {
        return new ArrayList<>(byId.keySet());
    }

    public static List<TrialDefinition> byMode(TrialMode mode)
    {
        List<TrialDefinition> result = new ArrayList<>();
        for (TrialDefinition definition : byId.values())
        {
            if (definition.mode == mode)
            {
                result.add(definition);
            }
        }
        result.sort(Comparator.comparing(TrialDefinition::id));
        return result;
    }

    /**
     * 这个传送门该开哪份试炼：绑定了就取绑定的那份；没绑定取该模式下 ID 字典序最靠前的一份。
     * 注意后者是「按完整 ID 字符串排序」，别的命名空间加一份 aaa:challenge 会抢走默认。
     */
    @Nullable
    public static TrialDefinition resolve(TrialMode mode, @Nullable String boundId)
    {
        if (boundId != null && !boundId.isBlank())
        {
            return byId.get(boundId);
        }
        List<TrialDefinition> candidates = byMode(mode);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 供校验与指令使用：按命名空间前缀过滤后的快照副本。 */
    public static Map<String, TrialDefinition> snapshot()
    {
        return new LinkedHashMap<>(byId);
    }

    public static boolean looksLikeId(String raw)
    {
        return raw != null && ResourceLocation.tryParse(raw) != null;
    }
}
