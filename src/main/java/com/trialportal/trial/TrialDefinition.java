package com.trialportal.trial;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份试炼定义 = 一个传送门可开启的内容。字段名与数据包 JSON 一一对应（snake_case）。
 * 加载时调用 {@link #resolve(String)} 做一次性校验，运行期只读。
 */
public class TrialDefinition
{
    public static final double DEFAULT_SPAWN_RADIUS = 6.0D;
    public static final double DEFAULT_SPAWN_HEIGHT = 3.0D;
    /** -1 = 用配置里的默认放弃距离；0 = 关闭范围检查。 */
    public static final double DEFAULT_ABANDON_DISTANCE = -1.0D;

    @SerializedName("mode")
    public TrialMode mode;

    @SerializedName("display_name")
    public String displayName;

    /** 手写覆盖模式默认的无尽标记；null = 按模式推断。 */
    @SerializedName("endless")
    public Boolean endlessOverride;

    @SerializedName("spawn_radius")
    public Double spawnRadius;

    @SerializedName("spawn_height")
    public Double spawnHeight;

    @SerializedName("abandon_distance")
    public Double abandonDistance;

    @SerializedName("waves")
    public List<Wave> waves = new ArrayList<>();

    @SerializedName("scaling")
    public ScalingRule scaling;

    @SerializedName("boss")
    public BossSpec boss;

    /** 校验通过后才填，运行期用；null 表示这份定义无效（不会进库）。 */
    private transient String resolvedId;

    public String id()
    {
        return this.resolvedId == null ? "" : this.resolvedId;
    }

    public boolean isEndless()
    {
        return this.endlessOverride != null ? this.endlessOverride : this.mode.isEndless();
    }

    public double spawnRadiusOrDefault()
    {
        return this.spawnRadius == null ? DEFAULT_SPAWN_RADIUS : this.spawnRadius;
    }

    public double spawnHeightOrDefault()
    {
        return this.spawnHeight == null ? DEFAULT_SPAWN_HEIGHT : this.spawnHeight;
    }

    public double abandonDistanceOrDefault()
    {
        return this.abandonDistance == null ? DEFAULT_ABANDON_DISTANCE : this.abandonDistance;
    }

    /**
     * @return null 表示通过；否则为失败原因（加载器会跳过这份定义并写日志）
     */
    @Nullable
    public String resolve(String id)
    {
        if (this.mode == null)
        {
            return "缺少 mode（challenge / tower / trial）";
        }
        if (this.displayName == null || this.displayName.isBlank())
        {
            this.displayName = id;
        }
        if (this.isEndless() && this.scaling == null)
        {
            return "无尽模式必须有 scaling 规则";
        }
        if (this.mode == TrialMode.TOWER && this.scaling == null)
        {
            return "爬塔模式必须有 scaling 规则";
        }
        if (this.mode == TrialMode.TRIAL && this.boss == null)
        {
            return "试炼模式必须指定 boss";
        }
        if (this.scaling != null)
        {
            String scalingError = this.scaling.resolve();
            if (scalingError != null)
            {
                return scalingError;
            }
        }
        if (!this.isEndless())
        {
            for (int i = 0; i < this.waves.size(); i++)
            {
                String waveError = this.waves.get(i).resolve();
                if (waveError != null)
                {
                    return "第 " + (i + 1) + " 波：" + waveError;
                }
            }
            if (this.waves.isEmpty() && this.boss == null)
            {
                return "既没有 waves 也没有 boss";
            }
        }
        if (this.boss != null)
        {
            String bossError = this.boss.resolve();
            if (bossError != null)
            {
                return bossError;
            }
        }
        this.resolvedId = id;
        return null;
    }

    /** 取第 waveIndex 波（0 起）的内容；无尽模式按层现算。返回 null 表示没有更多波次。 */
    @Nullable
    public WaveContent waveFor(int waveIndex)
    {
        if (this.isEndless())
        {
            return this.scaling == null ? null : this.scaling.build(waveIndex + 1);
        }
        Wave wave = waveIndex < this.waves.size() ? this.waves.get(waveIndex) : null;
        if (wave == null)
        {
            // 有限波次打完：试炼模式把 boss 当最后一波，其它模式结束
            if (this.mode == TrialMode.TRIAL && this.boss != null && waveIndex == this.waves.size())
            {
                WaveContent content = new WaveContent();
                content.title = this.boss.displayName(this.mode);
                MobEntry bossEntry = new MobEntry();
                bossEntry.entity = this.boss.entity;
                bossEntry.count = 1;
                bossEntry.boss = true;
                content.mobs.add(bossEntry);
                content.rewards.addAll(this.boss.rewards);
                return content;
            }
            return null;
        }
        WaveContent content = new WaveContent();
        content.title = wave.title == null ? "第 %s 波" : wave.title;
        content.mobs.addAll(wave.mobs);
        content.rewards.addAll(wave.rewards);
        content.intermissionTicks = wave.intermissionTicks;
        return content;
    }

    /** 本波之后到下一波的间隔（tick）；-1 = 用配置默认值。 */
    public int intermissionTicksFor(int waveIndex)
    {
        if (this.isEndless())
        {
            return this.scaling == null ? -1 : this.scaling.intermissionTicks;
        }
        Wave wave = waveIndex < this.waves.size() ? this.waves.get(waveIndex) : null;
        return wave == null ? -1 : wave.intermissionTicks;
    }

    /** 有限列表的波数；无尽模式返回 -1。 */
    public int finiteWaveCount()
    {
        if (this.isEndless())
        {
            return -1;
        }
        return this.waves.size() + (this.mode == TrialMode.TRIAL && this.boss != null ? 1 : 0);
    }

    public static class Wave
    {
        @SerializedName("title")
        public String title;

        @SerializedName("mobs")
        public List<MobEntry> mobs = new ArrayList<>();

        @SerializedName("rewards")
        public List<RewardEntry> rewards = new ArrayList<>();

        @SerializedName("intermission_ticks")
        public int intermissionTicks = -1;

        @Nullable
        String resolve()
        {
            if (this.mobs.isEmpty())
            {
                return "mobs 为空";
            }
            for (MobEntry mob : this.mobs)
            {
                String error = mob.resolve();
                if (error != null)
                {
                    return error;
                }
            }
            return null;
        }
    }

    public static class MobEntry
    {
        @SerializedName("entity")
        public String entity;

        @SerializedName("count")
        public int count = 1;

        /** 可选 SNBT，合并进实体。 */
        @SerializedName("nbt")
        public String nbt;

        @SerializedName("boss")
        public boolean boss;

        @Nullable
        String resolve()
        {
            if (this.entity == null || this.entity.isBlank())
            {
                return "mob 缺少 entity";
            }
            if (this.count <= 0)
            {
                return "mob " + this.entity + " 的 count 必须为正（当前 " + this.count + "）";
            }
            if (this.nbt != null && this.nbt.isBlank())
            {
                return "mob " + this.entity + " 的 nbt 是空串";
            }
            return null;
        }

        public ResourceLocation entityId()
        {
            return ResourceLocation.tryParse(this.entity);
        }
    }

    public static class RewardEntry
    {
        @SerializedName("item")
        public String item;

        @SerializedName("count")
        public int count = 1;

        @Nullable
        String resolve()
        {
            if (this.item == null || this.item.isBlank())
            {
                return "reward 缺少 item";
            }
            if (this.count <= 0)
            {
                return "reward " + this.item + " 的 count 必须为正";
            }
            return null;
        }
    }

    /** 一波的展开结果：状态机只看它，不再关心模式差异。 */
    public static class WaveContent
    {
        public String title;
        public final List<MobEntry> mobs = new ArrayList<>();
        public final List<RewardEntry> rewards = new ArrayList<>();
        public int intermissionTicks = -1;
        /** 无尽模式下当前是第几层；有限波次为 0。 */
        public int floor;
        /** 本波里按 BOSS 处理的条目下标（用于贴词条），-1 表示无。 */
        public int bossIndex = -1;
        /** 本波该用哪个词条池；null = 用全量已加载词条。 */
        public List<String> affixPool;
    }

    public static class ScalingRule
    {
        @SerializedName("health_multiplier_per_floor")
        public double healthMultiplierPerFloor = 0.18D;

        @SerializedName("damage_multiplier_per_floor")
        public double damageMultiplierPerFloor = 0.06D;

        @SerializedName("count_per_floor")
        public double countPerFloor = 0.2D;

        @SerializedName("max_count_per_entry")
        public int maxCountPerEntry = 12;

        @SerializedName("intermission_ticks")
        public int intermissionTicks = -1;

        @SerializedName("tiers")
        public List<Tier> tiers = new ArrayList<>();

        @SerializedName("rewards")
        public List<RewardEntry> rewards = new ArrayList<>();

        @SerializedName("reward_every_floors")
        public int rewardEveryFloors = 5;

        @SerializedName("boss_every_floors")
        public int bossEveryFloors = 0;

        @SerializedName("boss_affix_pool")
        public List<String> bossAffixPool;

        @Nullable
        String resolve()
        {
            if (this.tiers.isEmpty())
            {
                return "scaling.tiers 为空";
            }
            int coveredFrom = Integer.MAX_VALUE;
            int coveredTo = Integer.MIN_VALUE;
            for (Tier tier : this.tiers)
            {
                if (tier.from > tier.to)
                {
                    return "层段 " + tier.from + "-" + tier.to + " 的 from 大于 to";
                }
                if (tier.mobs.isEmpty())
                {
                    return "层段 " + tier.from + "-" + tier.to + " 的 mobs 为空";
                }
                for (String mob : tier.mobs)
                {
                    if (mob == null || mob.isBlank())
                    {
                        return "层段 " + tier.from + "-" + tier.to + " 含空 entity";
                    }
                }
                coveredFrom = Math.min(coveredFrom, tier.from);
                coveredTo = Math.max(coveredTo, tier.to);
            }
            if (coveredFrom > 1)
            {
                return "scaling.tiers 没有覆盖第 1 层（最早从 " + coveredFrom + " 开始）";
            }
            if (coveredTo < 9999)
            {
                return "scaling.tiers 只覆盖到第 " + coveredTo + " 层，无尽爬塔会在那之上取不到内容";
            }
            return null;
        }

        /**
         * 取第 floor 层（1 起）的内容。
         * 约定：BOSS 层固定取命中层段 mobs 的第一个当 BOSS（与是否配词条池无关），
         * 其余名额在候选列表里按层数轮换；每层的怪量随 count_per_floor 增长，并被 max_count_per_entry 钳制。
         */
        @Nullable
        WaveContent build(int floor)
        {
            Tier tier = null;
            for (Tier candidate : this.tiers)
            {
                if (floor >= candidate.from && floor <= candidate.to)
                {
                    tier = candidate;
                    break;
                }
            }
            if (tier == null)
            {
                return null;
            }
            boolean bossFloor = this.bossEveryFloors > 0 && floor % this.bossEveryFloors == 0 && tier.boss;
            WaveContent content = new WaveContent();
            content.floor = floor;
            content.title = "第 " + floor + " 层";

            int normalSlots = Math.max(1, tier.count + (int) Math.floor((floor - 1) * this.countPerFloor));
            normalSlots = Math.min(normalSlots, this.maxCountPerEntry);

            if (bossFloor)
            {
                MobEntry bossEntry = new MobEntry();
                bossEntry.entity = tier.mobs.get(0);
                bossEntry.count = 1;
                bossEntry.boss = true;
                content.bossIndex = content.mobs.size();
                content.affixPool = tier.bossAffixPool != null ? tier.bossAffixPool : this.bossAffixPool;
                content.mobs.add(bossEntry);
            }
            MobEntry normal = new MobEntry();
            normal.entity = tier.mobs.get(floor % tier.mobs.size());
            normal.count = normalSlots;
            content.mobs.add(normal);

            if (this.rewardEveryFloors > 0 && floor % this.rewardEveryFloors == 0)
            {
                content.rewards.addAll(this.rewards);
            }
            content.intermissionTicks = this.intermissionTicks;
            return content;
        }

        public double healthMultiplier(int floor)
        {
            return 1.0D + (floor - 1) * this.healthMultiplierPerFloor;
        }

        public double damageMultiplier(int floor)
        {
            return 1.0D + (floor - 1) * this.damageMultiplierPerFloor;
        }
    }

    public static class Tier
    {
        @SerializedName("from")
        public int from;

        @SerializedName("to")
        public int to;

        @SerializedName("mobs")
        public List<String> mobs = new ArrayList<>();

        @SerializedName("count")
        public int count = 4;

        /** 该层段是否整体按 BOSS 处理（BOSS 层取 mobs[0]）。 */
        @SerializedName("boss")
        public boolean boss;

        @SerializedName("boss_affix_pool")
        public List<String> bossAffixPool;
    }

    public static class BossSpec
    {
        @SerializedName("entity")
        public String entity;

        @SerializedName("name")
        public String name;

        /** -1 = 用配置默认词条数。 */
        @SerializedName("affix_count")
        public int affixCount = -1;

        @SerializedName("affix_pool")
        public List<String> affixPool;

        @SerializedName("rewards")
        public List<RewardEntry> rewards = new ArrayList<>();

        @Nullable
        String resolve()
        {
            if (this.entity == null || this.entity.isBlank())
            {
                return "boss 缺少 entity";
            }
            for (RewardEntry reward : this.rewards)
            {
                String error = reward.resolve();
                if (error != null)
                {
                    return "boss 奖励：" + error;
                }
            }
            return null;
        }

        String displayName(TrialMode mode)
        {
            return this.name == null ? "%s 的试炼" : this.name;
        }
    }
}
