package com.trialportal.trial;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份资源副本（闯关）：同一套玩法的三种难度写在一起。
 * <p>
 * 与 {@link TrialDefinition} 的分工是刻意的——{@code trials/*.json} 管「按波推进」的玩法（试炼、爬塔），
 * {@code challenges/*.json} 管「不计波次、持续补充、按杀计奖」的资源副本。一个玩法只有一份真相，
 * 不做「同一模式两处都能配」那种迟早对不上的设计。
 */
public class ChallengeDefinition
{
    /** 三档难度的固定键：少一档直接判定义无效，不做「缺省回退」——那会让改配置的人以为生效了。 */
    public static final List<String> DIFFICULTY_KEYS = List.of("easy", "normal", "hard");

    @SerializedName("display_name")
    public String displayName;

    /** 界面上的图标物品（只影响观感，不参与玩法）。 */
    @SerializedName("icon_item")
    public String iconItem = "minecraft:chest";

    /** 列表里的展示顺序，小的在前。 */
    @SerializedName("order")
    public int order;

    /** 一局有多久（tick）。到时统一结算送出场，不做「超时失败」惩罚。 */
    @SerializedName("duration_ticks")
    public int durationTicks = 6000;

    @SerializedName("difficulties")
    public Map<String, Difficulty> difficulties = new LinkedHashMap<>();

    private transient String resolvedId;

    public String id()
    {
        return this.resolvedId == null ? "" : this.resolvedId;
    }

    public String displayNameOrDefault()
    {
        return this.displayName == null || this.displayName.isBlank() ? this.id() : this.displayName;
    }

    @Nullable
    public Difficulty difficulty(@Nullable String key)
    {
        return key == null ? null : this.difficulties.get(key);
    }

    /** 默认难度：UI 与指令都没指定时用普通档。 */
    public Difficulty defaultDifficulty()
    {
        Difficulty normal = this.difficulties.get("normal");
        return normal != null ? normal : this.difficulties.get("easy");
    }

    /**
     * @return null 表示通过；否则为失败原因（加载器会跳过这份定义并写日志）
     */
    @Nullable
    public String resolve(String id)
    {
        if (this.durationTicks < 200)
        {
            return "duration_ticks 至少 200（10 秒），当前 " + this.durationTicks;
        }
        if (ResourceLocation.tryParse(this.iconItem) == null)
        {
            return "icon_item 不是合法物品 ID（" + this.iconItem + "）";
        }
        for (String key : DIFFICULTY_KEYS)
        {
            Difficulty difficulty = this.difficulties.get(key);
            if (difficulty == null)
            {
                return "缺少难度 " + key + "（三档 easy/normal/hard 都要有）";
            }
            String error = difficulty.resolve(id + "#" + key);
            if (error != null)
            {
                return error;
            }
        }
        for (String key : this.difficulties.keySet())
        {
            if (!DIFFICULTY_KEYS.contains(key))
            {
                return "不认识难度 " + key + "（只允许 " + String.join("/", DIFFICULTY_KEYS) + "）";
            }
        }
        this.resolvedId = id;
        if (this.displayName == null || this.displayName.isBlank())
        {
            this.displayName = id;
        }
        return null;
    }

    /** 一档难度：怪群、补充节奏、上限量、以及「杀一只攒什么」。 */
    public static class Difficulty
    {
        @SerializedName("display_name")
        public String displayName;

        @SerializedName("mobs")
        public List<TrialDefinition.MobEntry> mobs = new ArrayList<>();

        /** 同场上限：低于它才会继续补充。 */
        @SerializedName("mob_cap")
        public int mobCap = 12;

        /** 每次补充几只。 */
        @SerializedName("spawn_batch")
        public int spawnBatch = 3;

        @SerializedName("spawn_interval_ticks")
        public int spawnIntervalTicks = 100;

        /** 这一档的怪有多凶（血量/攻击/速度倍率 + 护甲/击退抗性加值），单只怪条目上还能再叠一层。 */
        @SerializedName("strength")
        public MobStrength strength;

        /** 补怪落点的水平半径；缺省 7（贴着隔间平台）。 */
        @SerializedName("spawn_radius")
        public Double spawnRadius;

        /** 补怪落点的上下搜索高度；缺省 2。 */
        @SerializedName("spawn_height")
        public Double spawnHeight;

        /** 杀一只往待结算池里加什么。空 = 这一档不给奖。 */
        @SerializedName("kill_rewards")
        public List<TrialDefinition.RewardEntry> killRewards = new ArrayList<>();

        public String displayNameOrDefault(String key)
        {
            return this.displayName == null || this.displayName.isBlank() ? key : this.displayName;
        }

        /** 补怪落点：这一档没写就用隔间平台边上的默认值（原来这两个数是 Java 常量，改一次要重编译）。 */
        public double spawnRadiusOrDefault()
        {
            return this.spawnRadius == null ? 7.0D : Math.max(1.0D, this.spawnRadius);
        }

        public double spawnHeightOrDefault()
        {
            return this.spawnHeight == null ? 2.0D : Math.max(1.0D, this.spawnHeight);
        }

        /**
         * 把 {@code mobs[]} 按各自 count 展开成一条轮转队列：这一档写僵尸×2 + 骷髅×1，
         * 补怪就按「僵尸,僵尸,骷髅」的比例循环。
         * <p>
         * 以前 count 被校验却没有任何人读——写 4 和写 1 在游戏里一模一样，正是"配了等于没配"。
         * count 是<b>配比</b>不是总量：场上有多少只由 {@code mob_cap} 与 {@code spawn_batch} 管。
         */
        public java.util.List<TrialDefinition.MobEntry> spawnPlan()
        {
            java.util.List<TrialDefinition.MobEntry> plan = new java.util.ArrayList<>();
            for (TrialDefinition.MobEntry entry : this.mobs)
            {
                for (int i = 0; i < Math.max(1, entry.count); i++)
                {
                    plan.add(entry);
                }
            }
            return plan;
        }

        /** 配比队列的上限：超过这个数说明作者把 count 当成"这波总共几只"在写了。 */
        public static final int MAX_PLAN_ENTRIES = 64;

        @Nullable
        public String resolve(String where)
        {
            if (this.mobs.isEmpty())
            {
                return where + " 的 mobs 是空的";
            }
            for (TrialDefinition.MobEntry entry : this.mobs)
            {
                if (entry == null || entry.entity == null || entry.entity.isBlank())
                {
                    return where + " 有一条 mob 缺 entity";
                }
                if (ResourceLocation.tryParse(entry.entity) == null)
                {
                    return where + " 的 entity 不是合法 ID（" + entry.entity + "）";
                }
                if (entry.count < 1)
                {
                    return where + " 的 " + entry.entity + " count 至少 1";
                }
            }
            if (this.mobCap < 1 || this.spawnBatch < 1)
            {
                return where + " 的 mob_cap / spawn_batch 都要 ≥1";
            }
            int plan = spawnPlan().size();
            if (plan > MAX_PLAN_ENTRIES)
            {
                return where + " 的 mobs 按 count 展开后有 " + plan + " 条，超过 " + MAX_PLAN_ENTRIES
                        + "：count 是配比不是总量，场上有多少只该由 mob_cap 管";
            }
            if (this.spawnIntervalTicks < 20)
            {
                return where + " 的 spawn_interval_ticks 太小（" + this.spawnIntervalTicks + "），一秒内补好几次没意义";
            }
            if (this.strength != null)
            {
                String strengthError = this.strength.resolve(where + " 的 strength");
                if (strengthError != null)
                {
                    return strengthError;
                }
            }
            if (this.spawnRadius != null && this.spawnRadius < 1.0D)
            {
                return where + " 的 spawn_radius 至少 1（当前 " + this.spawnRadius + "）";
            }
            if (this.spawnHeight != null && this.spawnHeight < 1.0D)
            {
                return where + " 的 spawn_height 至少 1（当前 " + this.spawnHeight + "）";
            }
            for (TrialDefinition.RewardEntry reward : this.killRewards)
            {
                if (reward == null || reward.item == null || reward.item.isBlank())
                {
                    return where + " 的 kill_rewards 有条目缺 item";
                }
                if (ResourceLocation.tryParse(reward.item) == null)
                {
                    return where + " 的 kill_rewards item 不是合法 ID（" + reward.item + "）";
                }
                if (reward.count < 1)
                {
                    return where + " 的 kill_rewards " + reward.item + " count 至少 1";
                }
            }
            return null;
        }
    }
}
