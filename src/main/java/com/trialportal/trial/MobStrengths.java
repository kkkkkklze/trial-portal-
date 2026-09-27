package com.trialportal.trial;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 把 {@link MobStrength} 落到一只怪身上。
 * <p>
 * 倍率一律走 {@code MULTIPLY_TOTAL} 而不是改基值：以前 {@code scaleForFloor} 用
 * {@code setBaseValue(base * m)}，于是"SNBT 里写的属性"和"倍率"谁先谁后决定结果——
 * 倍率把 SNBT 的基值吃掉，或者反过来。乘算 modifier 与基值无关，顺序不再有意义，
 * 两层倍率也自然相乘（1.5 × 1.5 = 2.25），与 {@link MobStrength#combined} 的语义一致。
 */
public final class MobStrengths
{
    private MobStrengths() {}

    public static void apply(Mob mob, @Nullable MobStrength strength)
    {
        if (strength == null || strength.isIdentity())
        {
            return;
        }
        boolean changed = multiply(mob, Attributes.MAX_HEALTH, "health", strength.healthMultiplier);
        changed |= multiply(mob, Attributes.ATTACK_DAMAGE, "damage", strength.damageMultiplier);
        changed |= multiply(mob, Attributes.MOVEMENT_SPEED, "speed", strength.speedMultiplier);
        changed |= add(mob, Attributes.ARMOR, "armor", strength.armorBonus);
        changed |= add(mob, Attributes.KNOCKBACK_RESISTANCE, "knockback", strength.knockbackResistanceBonus);
        if (changed)
        {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static boolean multiply(Mob mob, @Nullable Attribute attribute, String key, double multiplier)
    {
        return put(mob, attribute, key, multiplier - 1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static boolean add(Mob mob, @Nullable Attribute attribute, String key, double amount)
    {
        return put(mob, attribute, key, amount, AttributeModifier.Operation.ADDITION);
    }

    private static boolean put(Mob mob, @Nullable Attribute attribute, String key, double amount,
                               AttributeModifier.Operation operation)
    {
        if (attribute == null || Math.abs(amount) < 0.0001D)
        {
            return false;
        }
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null)
        {
            return false; // 这只怪没这条属性（例如纯远程怪没有 ATTACK_DAMAGE），跳过而不是报错
        }
        UUID uuid = modifierUuid(key);
        if (instance.getModifier(uuid) != null)
        {
            return false; // 同 UUID 再加会直接抛 IllegalArgumentException：本局只加一次
        }
        instance.addPermanentModifier(new AttributeModifier(uuid, "trial_portal:strength:" + key, amount, operation));
        return true;
    }

    /**
     * 每条属性一个固定 UUID：同一只怪身上"强度"改动的身份是确定的，重复应用不叠加。
     * 名字由调用点给死，不做注册表反查——UUID 一旦不稳定，重载后的怪就会叠出双倍血量。
     */
    private static UUID modifierUuid(String key)
    {
        return UUID.nameUUIDFromBytes(("trial_portal:strength:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
