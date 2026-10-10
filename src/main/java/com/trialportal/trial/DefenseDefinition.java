package com.trialportal.trial;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 守塔玩法的内容：{@code defenses/*.json}。
 * <p>
 * 与另外三种玩法的差别只有一件事：<b>怪有两个目标</b>——打玩家，以及拆那座塔。
 * 波次按时间自己往前走（不像试炼那样停下等玩家点继续），塔血归零或玩家死亡都判负。
 * 波次结构直接复用 {@link TrialDefinition.Wave}，不再另写一套 mobs/rewards/strength，
 * 免得同一件事在两个类里各长一份。
 */
public class DefenseDefinition
{
    /** 结界半径的取值范围：太小怪踩不进视野，太大超出一个隔间会啃到邻居家。 */
    public static final int MIN_WARD_RADIUS = 8;
    public static final int MAX_WARD_RADIUS = 30;
    /** 一波最多几种怪：再多波次表就没人读得懂了。 */
    public static final int MAX_WAVE_ENTRIES = 12;

    @SerializedName("comment")
    public String comment;

    @SerializedName("display_name")
    public String displayName;

    @SerializedName("icon_item")
    public String iconItem = "minecraft:end_rod";

    @SerializedName("order")
    public int order;

    /** 结界半径（格）。怪只在这么远的环外生成，玩家在里面。 */
    @SerializedName("ward_radius")
    public int wardRadius = 16;

    /**
     * 塔的血量池。怪拆塔扣这里，归零判负。
     * 走血量池而不是真破坏方块：场地内禁止破坏/放置是已定契约（§9 第 6 条），
     * 真啃方块既要管重建又会和它打架；血归零那一刻让塔整块塌掉，观感一样。
     */
    @SerializedName("tower_health")
    public double towerHealth = 300.0D;

    /** 波与波之间的间歇（tick）。守塔不等玩家，时间到就压下一波。 */
    @SerializedName("intermission_ticks")
    public int intermissionTicks = 200;

    /**
     * 一波里有多大比例怪直奔塔（其余锁玩家）。这就是「怪有两个目标」的落点：
     * 0 = 全打人（塔永远掉不了血），1 = 全拆塔（玩家挨不到打）。
     */
    @SerializedName("tower_focus_share")
    public double towerFocusShare = 0.5D;

    /** 拆塔的怪每下一口扣多少塔血。 */
    @SerializedName("tower_damage_per_hit")
    public double towerDamagePerHit = 2.0D;

    /** 同一只怪两次拆塔之间的间隔（tick）。 */
    @SerializedName("tower_hit_interval_ticks")
    public int towerHitIntervalTicks = 20;

    /** 定义级强度，波次自己写的 {@code strength} 在它之上叠。 */
    @SerializedName("strength")
    public MobStrength strength;

    @SerializedName("waves")
    public List<TrialDefinition.Wave> waves = new ArrayList<>();

    /** 守住全部波次的通关奖励（与波内 rewards 叠加，不替换）。 */
    @SerializedName("rewards")
    public List<TrialDefinition.RewardEntry> rewards = new ArrayList<>();

    /** 波里 {@code boss: true} 那只贴哪些词条；空 = 用全量已加载词条。 */
    @SerializedName("boss_affix_pool")
    public List<String> bossAffixPool = new ArrayList<>();

    private transient String resolvedId;

    public String id()
    {
        return this.resolvedId == null ? "" : this.resolvedId;
    }

    public String displayNameOrDefault()
    {
        return this.displayName == null || this.displayName.isBlank() ? this.id() : this.displayName;
    }

    /**
     * 开局时发给会话的那一份副本。
     * <p>
     * 必须有：{@code /tei defense} 改的是**这一场**的参数，直接改 {@code DefenseLibrary} 里那份的话，
     * 同一个整合包下别人开局、或 /reload 之前任何人开局都会拿到被改过的内容（注册表外泄漏）。
     * 逐条 copies（含 mobs/rewards/strength），不是 {@code List.copyOf} 那种浅拷贝——
     * 浅拷贝只挡住列表本身，改一只怪的 count 照样会写穿到库里的条目上。
     */
    public DefenseDefinition copy()
    {
        DefenseDefinition copy = new DefenseDefinition();
        copy.comment = this.comment;
        copy.displayName = this.displayName;
        copy.iconItem = this.iconItem;
        copy.order = this.order;
        copy.wardRadius = this.wardRadius;
        copy.towerHealth = this.towerHealth;
        copy.intermissionTicks = this.intermissionTicks;
        copy.towerFocusShare = this.towerFocusShare;
        copy.towerDamagePerHit = this.towerDamagePerHit;
        copy.towerHitIntervalTicks = this.towerHitIntervalTicks;
        copy.strength = this.strength == null ? null : MobStrength.combined(null, copyStrength(this.strength));
        copy.rewards = copyRewards(this.rewards);
        copy.bossAffixPool = new ArrayList<>(this.bossAffixPool);
        for (TrialDefinition.Wave wave : this.waves)
        {
            copy.waves.add(copyWave(wave));
        }
        copy.resolvedId = this.resolvedId;
        return copy;
    }

    private static MobStrength copyStrength(MobStrength source)
    {
        MobStrength copy = new MobStrength();
        copy.healthMultiplier = source.healthMultiplier;
        copy.damageMultiplier = source.damageMultiplier;
        copy.speedMultiplier = source.speedMultiplier;
        copy.armorBonus = source.armorBonus;
        copy.knockbackResistanceBonus = source.knockbackResistanceBonus;
        return copy;
    }

    private static List<TrialDefinition.RewardEntry> copyRewards(List<TrialDefinition.RewardEntry> source)
    {
        List<TrialDefinition.RewardEntry> copy = new ArrayList<>();
        for (TrialDefinition.RewardEntry entry : source)
        {
            TrialDefinition.RewardEntry cloned = new TrialDefinition.RewardEntry();
            cloned.item = entry.item;
            cloned.count = entry.count;
            copy.add(cloned);
        }
        return copy;
    }

    private static TrialDefinition.Wave copyWave(TrialDefinition.Wave source)
    {
        TrialDefinition.Wave copy = new TrialDefinition.Wave();
        copy.title = source.title;
        copy.intermissionTicks = source.intermissionTicks;
        copy.strength = source.strength == null ? null : copyStrength(source.strength);
        for (TrialDefinition.MobEntry entry : source.mobs)
        {
            TrialDefinition.MobEntry cloned = new TrialDefinition.MobEntry();
            cloned.entity = entry.entity;
            cloned.count = entry.count;
            cloned.nbt = entry.nbt;
            cloned.boss = entry.boss;
            cloned.strength = entry.strength == null ? null : copyStrength(entry.strength);
            copy.mobs.add(cloned);
        }
        for (TrialDefinition.RewardEntry reward : source.rewards)
        {
            TrialDefinition.RewardEntry cloned = new TrialDefinition.RewardEntry();
            cloned.item = reward.item;
            cloned.count = reward.count;
            copy.rewards.add(cloned);
        }
        return copy;
    }

    /** 波数。守塔没有无尽档：塔血与波次表是作者写死的，打完就是守住。 */
    public int finiteWaveCount()
    {
        return this.waves.size();
    }

    /** 这一波之后歇多久（tick）：波里没写（-1）就用定义级的默认。 */
    public int intermissionTicksFor(int waveIndex)
    {
        TrialDefinition.Wave wave = waveIndex >= 0 && waveIndex < this.waves.size() ? this.waves.get(waveIndex) : null;
        return wave != null && wave.intermissionTicks >= 0 ? wave.intermissionTicks : this.intermissionTicks;
    }

    /**
     * 取第 waveIndex 波（0 起）展开后的内容；越界返回 null——「取不到下一波」就是通关的触发条件，
     * 所以这条 null 分支得有判据盯着（见 TrialSelfTest 的守塔波次阶梯）。
     * <p>
     * 定义级倍率只在这里乘一次，波次级叠在它之上；单只怪那一层留在生成点再叠。
     */
    @Nullable
    public TrialDefinition.WaveContent waveFor(int waveIndex)
    {
        if (waveIndex < 0 || waveIndex >= this.waves.size())
        {
            return null;
        }
        TrialDefinition.Wave wave = this.waves.get(waveIndex);
        if (wave == null)
        {
            return null;
        }
        TrialDefinition.WaveContent content = new TrialDefinition.WaveContent();
        content.title = wave.title == null ? "第 " + (waveIndex + 1) + " 波" : wave.title;
        content.mobs.addAll(wave.mobs);
        content.rewards.addAll(wave.rewards);
        content.intermissionTicks = wave.intermissionTicks;
        content.strength = MobStrength.combined(this.strength, wave.strength);
        // 池子空 = 交回 BossAffixLibrary 用全量已加载词条，与试炼那条路同一个口径
        content.affixPool = this.bossAffixPool.isEmpty() ? null : this.bossAffixPool;
        return content;
    }

    /**
     * @return null 表示通过；否则失败原因（加载器会跳过这份定义并写日志）
     */
    @Nullable
    public String resolve(String id)
    {
        this.resolvedId = id;
        if (ResourceLocation.tryParse(this.iconItem) == null)
        {
            return "icon_item 不是合法 ID（" + this.iconItem + "）";
        }
        if (this.wardRadius < MIN_WARD_RADIUS || this.wardRadius > MAX_WARD_RADIUS)
        {
            return "ward_radius 要在 " + MIN_WARD_RADIUS + '~' + MAX_WARD_RADIUS + " 之间，当前 " + this.wardRadius;
        }
        if (this.towerHealth <= 0.0D)
        {
            return "tower_health 必须为正（塔一开局就归零等于直接判负），当前 " + this.towerHealth;
        }
        if (this.towerHealth > 1.0E7D)
        {
            return "tower_health 大得不像话（" + this.towerHealth + "），多半是把百分比写成了倍率";
        }
        if (this.intermissionTicks < 20)
        {
            return "intermission_ticks 至少 20（一秒），当前 " + this.intermissionTicks;
        }
        if (this.towerFocusShare <= 0.0D || this.towerFocusShare >= 1.0D)
        {
            return "tower_focus_share 要在 0~1 之间且不取端点（当前 " + this.towerFocusShare
                    + "）：0 = 没有怪拆塔，那条判负分支永远走不到；1 = 没有怪打玩家，玩家站在原地就赢";
        }
        if (this.towerDamagePerHit <= 0.0D)
        {
            return "tower_damage_per_hit 必须为正（当前 " + this.towerDamagePerHit + "），否则怪啃塔不掉血";
        }
        if (this.towerDamagePerHit > this.towerHealth)
        {
            return "tower_damage_per_hit " + this.towerDamagePerHit + " 比 tower_health " + this.towerHealth
                    + " 还大——一口就把塔啃没了，玩家来不及反应";
        }
        if (this.towerHitIntervalTicks < 5)
        {
            return "tower_hit_interval_ticks 至少 5（当前 " + this.towerHitIntervalTicks
                    + "），再密就变成每 tick 扣血，塔血撑不过两秒";
        }
        if (this.waves.isEmpty())
        {
            return "waves 是空的：守塔至少要有波次";
        }
        if (this.strength != null)
        {
            String strengthError = this.strength.resolve("strength");
            if (strengthError != null)
            {
                return strengthError;
            }
        }
        for (int index = 0; index < this.waves.size(); index++)
        {
            TrialDefinition.Wave wave = this.waves.get(index);
            if (wave == null)
            {
                return "第 " + (index + 1) + " 波是空条目";
            }
            if (wave.mobs.size() > MAX_WAVE_ENTRIES)
            {
                return "第 " + (index + 1) + " 波写了 " + wave.mobs.size() + " 种怪，超过 "
                        + MAX_WAVE_ENTRIES + " 种就没人在游戏里看得懂这波了";
            }
            String error = wave.resolve();
            if (error != null)
            {
                return "第 " + (index + 1) + " 波：" + error;
            }
        }
        return TrialDefinition.checkRewards("通关 rewards", this.rewards);
    }
}
