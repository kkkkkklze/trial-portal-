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

    /** 这份定义里所有怪的默认强度（波次级与单只怪级在它基础上再乘/再加）。 */
    @SerializedName("strength")
    public MobStrength strength;

    /** 一波最多生成几只；0 = 用 {@code general.max_spawns_per_wave}。 */
    @SerializedName("max_spawns_per_wave")
    public int maxSpawnsPerWave;

    /** 同时存活上限；0 = 用 {@code general.max_alive_mobs}。 */
    @SerializedName("max_alive_mobs")
    public int maxAliveMobs;

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

    /** 一波的强度链：定义级 → 波次级。单只怪那一层由生成点再叠一次（{@code mob.strength}）。 */
    @Nullable
    public MobStrength strengthFor(@Nullable MobStrength waveLevel)
    {
        return MobStrength.combined(this.strength, waveLevel);
    }

    /** 一波生成几只的上限：这份定义没写就用全局配置。 */
    public int spawnBudgetOrDefault()
    {
        return this.maxSpawnsPerWave > 0 ? this.maxSpawnsPerWave
                : com.trialportal.config.TrialConfig.GENERAL_MAX_SPAWNS_PER_WAVE.get();
    }

    /** 同时存活上限：这份定义没写就用全局配置。 */
    public int maxAliveOrDefault()
    {
        return this.maxAliveMobs > 0 ? this.maxAliveMobs
                : com.trialportal.config.TrialConfig.GENERAL_MAX_ALIVE_MOBS.get();
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
        String strengthError = this.strength == null ? null : this.strength.resolve("strength");
        if (strengthError != null)
        {
            return strengthError;
        }
        if (this.maxSpawnsPerWave < 0 || this.maxSpawnsPerWave > 512)
        {
            return "max_spawns_per_wave 要在 0~512（0 = 用 general 里的默认），当前 " + this.maxSpawnsPerWave;
        }
        if (this.maxAliveMobs < 0 || this.maxAliveMobs > 512)
        {
            return "max_alive_mobs 要在 0~512（0 = 用 general 里的默认），当前 " + this.maxAliveMobs;
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
            WaveContent scaled = this.scaling == null ? null : this.scaling.build(waveIndex + 1);
            if (scaled != null)
            {
                scaled.strength = MobStrength.combined(this.strength, scaled.strength);
            }
            return scaled;
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
                content.affixPool = this.boss.affixPool;
                content.strength = MobStrength.combined(this.strength, this.boss.strength);
                return content;
            }
            return null;
        }
        WaveContent content = new WaveContent();
        content.title = wave.title == null ? "第 %s 波" : wave.title;
        content.mobs.addAll(wave.mobs);
        content.rewards.addAll(wave.rewards);
        content.intermissionTicks = wave.intermissionTicks;
        content.strength = MobStrength.combined(this.strength, wave.strength);
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

        /** 这一波所有怪的强度（在定义级基础上再乘/再加）。 */
        @SerializedName("strength")
        public MobStrength strength;

        @Nullable
        String resolve()
        {
            if (this.mobs.isEmpty())
            {
                return "mobs 为空";
            }
            String strengthError = this.strength == null ? null : this.strength.resolve("wave.strength");
            if (strengthError != null)
            {
                return strengthError;
            }
            for (MobEntry mob : this.mobs)
            {
                String error = mob.resolve();
                if (error != null)
                {
                    return error;
                }
            }
            return checkRewards("wave.rewards", this.rewards);
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

        /** 这只怪的强度（在波次级基础上再乘/再加）。 */
        @SerializedName("strength")
        public MobStrength strength;

        /**
         * 这只怪的恶意等级（装了「恶意」才有效）。用包装类型而不是 int：0 是「明说要零级」，
         * null 才是「没写、跟着层数/档位算」。写成 int 的话两者分不开，作者填 0 就等于关掉了整个联动。
         */
        @SerializedName("malice_level")
        @Nullable
        public Integer maliceLevel;

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
            if (this.nbt != null && !this.nbt.isBlank())
            {
                // SNBT 写错了以前只有运行期一条 warn（等于这只怪的装备/名字静默没生效），现在就报错
                try
                {
                    net.minecraft.nbt.TagParser.parseTag(this.nbt);
                }
                catch (Exception exception)
                {
                    return "mob " + this.entity + " 的 nbt 不是合法 SNBT：" + exception.getMessage();
                }
            }
            if (this.maliceLevel != null && this.maliceLevel < 0)
            {
                return "mob " + this.entity + " 的 malice_level 不能为负（当前 " + this.maliceLevel
                        + "）：要这一档不带恶意就写 0，不想管就别写这个字段";
            }
            if (this.maliceLevel != null && this.maliceLevel > MaliceLink.LEVEL_CEILING)
            {
                return "mob " + this.entity + " 的 malice_level=" + this.maliceLevel + " 超过 "
                        + MaliceLink.LEVEL_CEILING
                        + "，和配置项 malice.max_level 的上限差得太远，多半是把血量写进了这里";
            }
            return this.strength == null ? null : this.strength.resolve("mob " + this.entity + " 的 strength");
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
        /** 本波的强度（定义级与波次/层段级已经乘好了）；单只怪那一层在生成点再叠。 */
        @Nullable
        public MobStrength strength;
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

        /**
         * 逐层指定：某几层想固定出什么怪就写在这儿，没写的层继续走 {@code tiers} 的轮换。
         * 光靠 tiers 做不到"第 12 层一定是 2 只苦力怕 + 1 只僵尸"——那里取的是
         * {@code mobs.get(floor % size)}，出什么取决于列表顺序。
         */
        @SerializedName("floors")
        public List<FloorOverride> floors = new ArrayList<>();

        @Nullable
        FloorOverride overrideFor(int floor)
        {
            for (FloorOverride override : this.floors)
            {
                if (override.floor == floor)
                {
                    return override;
                }
            }
            return null;
        }

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
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (FloorOverride override : this.floors)
            {
                if (override.floor < 1)
                {
                    return "scaling.floors 的 floor 要从 1 起（当前 " + override.floor + "）";
                }
                if (!seen.add(override.floor))
                {
                    // 用 Map 配就会撞这个：JSON 里同名的后一份静默覆盖前一份，谁都不知道
                    return "scaling.floors 里第 " + override.floor + " 层配了两次";
                }
                if (override.floor > coveredTo)
                {
                    return "scaling.floors 第 " + override.floor + " 层超出 tiers 覆盖范围（到 " + coveredTo + "），"
                            + "这一层取不到内容";
                }
                String error = override.resolve();
                if (error != null)
                {
                    return error;
                }
            }
            return checkRewards("scaling.rewards", this.rewards);
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
            FloorOverride override = overrideFor(floor);
            if (override != null)
            {
                return buildOverridden(floor, tier, override);
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
            // 按层线性放大与层段基准强度合成一份；定义级那一层由 waveFor 再乘一次
            content.strength = MobStrength.combined(
                    MobStrength.perFloor(healthMultiplier(floor), damageMultiplier(floor)), tier.strength);
            return content;
        }

        /** 逐层指定的那一层：作者写什么就出什么，只继承层段的词条池与按层放大的倍率。 */
        private WaveContent buildOverridden(int floor, Tier tier, FloorOverride override)
        {
            WaveContent content = new WaveContent();
            content.floor = floor;
            content.title = override.title == null ? "第 " + floor + " 层" : override.title;
            for (MobEntry authored : override.mobs)
            {
                // 复制一份再交给状态机：模型对象是共用的，spawnOne 那边会往条目上打 boss 标记
                MobEntry copy = new MobEntry();
                copy.entity = authored.entity;
                copy.count = authored.count;
                copy.nbt = authored.nbt;
                copy.strength = authored.strength;
                copy.maliceLevel = authored.maliceLevel;
                copy.boss = authored.boss;
                if (copy.boss)
                {
                    content.bossIndex = content.mobs.size();
                }
                content.mobs.add(copy);
            }
            boolean bossFloor;
            if (override.boss != null)
            {
                bossFloor = override.boss;               // 这一层明确说了是不是 BOSS 层
            }
            else if (content.bossIndex >= 0)
            {
                bossFloor = true;                        // 作者在某条 mob 上直接标了 boss
            }
            else
            {
                bossFloor = this.bossEveryFloors > 0 && floor % this.bossEveryFloors == 0 && tier.boss;
            }
            if (bossFloor && content.bossIndex < 0 && !content.mobs.isEmpty())
            {
                content.mobs.get(0).boss = true;
                content.bossIndex = 0;
            }
            content.affixPool = override.bossAffixPool != null ? override.bossAffixPool
                    : (tier.bossAffixPool != null ? tier.bossAffixPool : this.bossAffixPool);
            if (this.rewardEveryFloors > 0 && floor % this.rewardEveryFloors == 0)
            {
                content.rewards.addAll(this.rewards);
            }
            content.rewards.addAll(override.rewards);
            content.intermissionTicks = this.intermissionTicks;
            content.strength = MobStrength.combined(
                    MobStrength.combined(MobStrength.perFloor(healthMultiplier(floor), damageMultiplier(floor)),
                            tier.strength), override.strength);
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

    /** 一层的具体编排：写死的怪组，不走层段轮换。 */
    public static class FloorOverride
    {
        @SerializedName("floor")
        public int floor;

        @SerializedName("title")
        public String title;

        @SerializedName("mobs")
        public List<MobEntry> mobs = new ArrayList<>();

        /** null = 由 {@code boss_every_floors} 与层段的 boss 决定；true/false = 这一层明确说了算。 */
        @SerializedName("boss")
        public Boolean boss;

        @SerializedName("boss_affix_pool")
        public List<String> bossAffixPool;

        /** 这一层额外发的奖励（与 {@code reward_every_floors} 那份叠加，不是替换）。 */
        @SerializedName("rewards")
        public List<RewardEntry> rewards = new ArrayList<>();

        @SerializedName("strength")
        public MobStrength strength;

        @Nullable
        String resolve()
        {
            if (this.mobs.isEmpty())
            {
                return "第 " + this.floor + " 层的 floors.mobs 为空";
            }
            if (this.boss != null && this.boss)
            {
                long flagged = this.mobs.stream().filter(mob -> mob.boss).count();
                if (flagged > 1)
                {
                    return "第 " + this.floor + " 层标了 " + flagged + " 只 boss，一层只认一个 BOSS 条目";
                }
            }
            String strengthError = this.strength == null ? null : this.strength.resolve(
                    "第 " + this.floor + " 层的 strength");
            if (strengthError != null)
            {
                return strengthError;
            }
            for (MobEntry mob : this.mobs)
            {
                String error = mob.resolve();
                if (error != null)
                {
                    return "第 " + this.floor + " 层：" + error;
                }
            }
            return checkRewards("第 " + this.floor + " 层的 rewards", this.rewards);
        }
    }

    /**
     * 奖励列表逐个校验。四处都得有份：以前只有 BOSS 那份查，
     * 于是 {@code waves[].rewards} 与 {@code scaling.floors[].rewards} 里写 count=0 或漏写 item
     * 都能加载成功，运行期再被 {@code Math.max(1, count)} 静默夹成 1 —— 发出去的东西和作者写的不是同一个。
     */
    @Nullable
    static String checkRewards(String where, List<RewardEntry> rewards)
    {
        for (RewardEntry reward : rewards)
        {
            String error = reward == null ? "有空条目" : reward.resolve();
            if (error != null)
            {
                return where + "：" + error;
            }
        }
        return null;
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

        /** 这个层段的基准强度（与按层线性放大的倍率相乘）。 */
        @SerializedName("strength")
        public MobStrength strength;
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

        /** 这只终局 BOSS 的强度（叠在定义级之上；终局波没有单独的 mob 条目可写）。 */
        @SerializedName("strength")
        public MobStrength strength;

        @Nullable
        String resolve()
        {
            if (this.entity == null || this.entity.isBlank())
            {
                return "boss 缺少 entity";
            }
            if (this.strength != null)
            {
                String strengthError = this.strength.resolve("boss.strength");
                if (strengthError != null)
                {
                    return strengthError;
                }
            }
            return checkRewards("boss.rewards", this.rewards);
        }

        String displayName(TrialMode mode)
        {
            return this.name == null ? "%s 的试炼" : this.name;
        }
    }
}
