package com.trialportal.boss;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * BOSS 词条库：加载、按玩家画像抽样。把词条贴到 BOSS 身上的逻辑在 {@link #applyTo} 里（同类的运行期生效点在 BossAffixRuntime）。
 */
public final class BossAffixLibrary
{
    private static volatile Map<String, BossAffix> byId = new TreeMap<>();

    private BossAffixLibrary() {}

    public static void replace(Map<String, BossAffix> loaded)
    {
        byId = new TreeMap<>(loaded);
    }

    @Nullable
    public static BossAffix get(String id)
    {
        return byId.get(id);
    }

    public static List<String> ids()
    {
        return new ArrayList<>(byId.keySet());
    }

    public static Map<String, BossAffix> snapshot()
    {
        return new LinkedHashMap<>(byId);
    }

    /** 玩家会被哪些词条命中（/tei trial profile 用）。 */
    public static List<String> matchingFor(PlayerProfile profile)
    {
        List<String> result = new ArrayList<>();
        for (BossAffix affix : byId.values())
        {
            if (affix.matches(profile))
            {
                result.add(affix.id());
            }
        }
        return result;
    }

    /**
     * 按画像筛候选，再按权重不放回抽样。
     *
     * @param pool           限定候选 ID；null / 空 = 用全部已加载词条
     * @param count          要抽几条；&lt;=0 时返回空
     * @param weightExponent 权重指数，调大更偏向高权重词条
     * @return 抽中的词条，顺序即应用顺序
     */
    public static List<BossAffix> sample(@Nullable Collection<String> pool, PlayerProfile profile,
                                         int count, double weightExponent, RandomSource random)
    {
        List<BossAffix> candidates = new ArrayList<>();
        if (pool == null || pool.isEmpty())
        {
            for (BossAffix affix : byId.values())
            {
                if (affix.matches(profile))
                {
                    candidates.add(affix);
                }
            }
        }
        else
        {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : pool)
            {
                if (id == null || !seen.add(id))
                {
                    continue;
                }
                BossAffix affix = byId.get(id);
                if (affix != null && affix.matches(profile))
                {
                    candidates.add(affix);
                }
            }
        }
        List<BossAffix> picked = new ArrayList<>();
        int wanted = Math.min(count, candidates.size());
        for (int i = 0; i < wanted; i++)
        {
            double total = 0.0D;
            for (BossAffix affix : candidates)
            {
                total += weightOf(affix, weightExponent);
            }
            if (total <= 0.0D)
            {
                picked.add(candidates.remove(0));
                continue;
            }
            double roll = random.nextDouble() * total;
            int index = 0;
            for (int j = 0; j < candidates.size(); j++)
            {
                roll -= weightOf(candidates.get(j), weightExponent);
                if (roll <= 0.0D)
                {
                    index = j;
                    break;
                }
            }
            picked.add(candidates.remove(index));
        }
        return picked;
    }

    private static double weightOf(BossAffix affix, double exponent)
    {
        double weight = Math.max(0.0D, affix.weight);
        if (weight == 0.0D)
        {
            return 0.0D;
        }
        return exponent == 1.0D ? weight : Math.pow(weight, exponent);
    }

    /** 词条引用的 ID 是否存在（checkTrialData 与设计校验共用）。 */
    public static boolean exists(String id)
    {
        return id != null && byId.containsKey(id);
    }

    public static ResourceLocation parse(String id)
    {
        return ResourceLocation.tryParse(id);
    }
}
