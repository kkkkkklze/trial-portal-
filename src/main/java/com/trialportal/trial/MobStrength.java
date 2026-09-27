package com.trialportal.trial;

import org.jetbrains.annotations.Nullable;

/**
 * 一只怪有多凶。纯数据类：不 import 任何 Minecraft 类型，所以"链式相乘"这部分能在无头门里直接断言。
 * <p>
 * 挂在三层上，逐字段相乘（乘数）或相加（加值）：定义级 → 波次/难度级 → 单只怪级。
 * 老字段（{@code scaling.*_multiplier_per_floor}、副本的 {@code health_multiplier}）折进同一条链，
 * 现有数据包不用改。
 */
public class MobStrength
{
    /** 最大血量倍率，1.0 = 不放大。 */
    @com.google.gson.annotations.SerializedName("health_multiplier")
    public double healthMultiplier = 1.0D;
    /** 攻击力倍率（实体没有 ATTACK_DAMAGE 属性时这条自动无效，例如骷髅这类纯远程怪）。 */
    @com.google.gson.annotations.SerializedName("damage_multiplier")
    public double damageMultiplier = 1.0D;
    /** 移动速度倍率。 */
    @com.google.gson.annotations.SerializedName("speed_multiplier")
    public double speedMultiplier = 1.0D;
    /** 护甲加值（点，直接加在基础值上）。 */
    @com.google.gson.annotations.SerializedName("armor_bonus")
    public double armorBonus;
    /** 击退抗性加值（0~1，原版上限会被夹）。 */
    @com.google.gson.annotations.SerializedName("knockback_resistance")
    public double knockbackResistanceBonus;

    public static MobStrength identity()
    {
        return new MobStrength();
    }

    public boolean isIdentity()
    {
        return nearOne(healthMultiplier) && nearOne(damageMultiplier) && nearOne(speedMultiplier)
                && armorBonus == 0.0D && knockbackResistanceBonus == 0.0D;
    }

    /** 两者逐字段合成一个：乘数相乘、加值相加。任一为 null 就返回另一个。 */
    @Nullable
    public static MobStrength combined(@Nullable MobStrength outer, @Nullable MobStrength inner)
    {
        if (outer == null)
        {
            return inner;
        }
        if (inner == null)
        {
            return outer;
        }
        MobStrength merged = new MobStrength();
        merged.healthMultiplier = outer.healthMultiplier * inner.healthMultiplier;
        merged.damageMultiplier = outer.damageMultiplier * inner.damageMultiplier;
        merged.speedMultiplier = outer.speedMultiplier * inner.speedMultiplier;
        merged.armorBonus = outer.armorBonus + inner.armorBonus;
        merged.knockbackResistanceBonus = outer.knockbackResistanceBonus + inner.knockbackResistanceBonus;
        return merged;
    }

    /** 按层数线性放大出来的强度（爬塔用），合成进链里。 */
    public static MobStrength perFloor(double healthMultiplier, double damageMultiplier)
    {
        MobStrength strength = new MobStrength();
        strength.healthMultiplier = healthMultiplier;
        strength.damageMultiplier = damageMultiplier;
        return strength;
    }

    /**
     * @return null 表示通过，否则为失败原因
     */
    @Nullable
    public String resolve(String where)
    {
        if (healthMultiplier < 0.0D || damageMultiplier < 0.0D || speedMultiplier < 0.0D)
        {
            return where + " 的倍率不能为负（health=" + healthMultiplier + " damage=" + damageMultiplier
                    + " speed=" + speedMultiplier + "）";
        }
        if (healthMultiplier > 100.0D || damageMultiplier > 100.0D || speedMultiplier > 10.0D)
        {
            return where + " 的倍率大得不像话（health=" + healthMultiplier + " damage=" + damageMultiplier
                    + " speed=" + speedMultiplier + "），多半是把百分比写成了倍率（150 应该是 1.5）";
        }
        if (armorBonus < 0.0D || armorBonus > 100.0D)
        {
            return where + " 的 armor_bonus 要在 0~100（当前 " + armorBonus + "）";
        }
        if (knockbackResistanceBonus < 0.0D || knockbackResistanceBonus > 1.0D)
        {
            return where + " 的 knockback_resistance 要在 0~1（当前 " + knockbackResistanceBonus + "）";
        }
        return null;
    }

    private static boolean nearOne(double value)
    {
        return Math.abs(value - 1.0D) < 0.0001D;
    }
}
