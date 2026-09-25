package com.trialportal.boss;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一条 BOSS 词条 = 判据（什么玩家会遇到它）+ 效果（它把 BOSS 改成什么样）。
 * 词条自身不做状态，应用逻辑在 BossAffixLibrary / BossAffixRuntime。
 */
public class BossAffix
{
    @SerializedName("display_name")
    public String displayName;

    @SerializedName("weight")
    public double weight = 1.0D;

    @SerializedName("counters")
    public CounterSpec counters;

    /** 属性注册名 → 修改。 */
    @SerializedName("attributes")
    public Map<String, AttributeMod> attributes = new LinkedHashMap<>();

    @SerializedName("immune_to")
    public List<String> immuneTo = new ArrayList<>();

    /** 空串 = 不改；否则把 BOSS 的近战伤害换成该伤害类型（如 minecraft:magic 用来无视护甲）。 */
    @SerializedName("melee_damage_type")
    public String meleeDamageType = "";

    @SerializedName("effects")
    public List<EffectSpec> effects = new ArrayList<>();

    /** 槽位名 → 物品 ID。 */
    @SerializedName("equipment")
    public Map<String, String> equipment = new LinkedHashMap<>();

    /** 槽位名 → ["附魔ID:等级", ...]。 */
    @SerializedName("equipment_enchantments")
    public Map<String, List<String>> equipmentEnchantments = new LinkedHashMap<>();

    @SerializedName("name_suffix")
    public String nameSuffix = "";

    /** 6 位十六进制，如 FF5555。 */
    @SerializedName("name_color")
    public String nameColor = "";

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
     * @return null 表示通过；否则为失败原因
     */
    @Nullable
    public String resolve(String id)
    {
        if (this.counters == null)
        {
            return "没有写 counters（词条永远不会被命中）";
        }
        if (this.weight <= 0.0D)
        {
            return "weight 必须为正（当前 " + this.weight + "）";
        }
        for (Map.Entry<String, AttributeMod> entry : this.attributes.entrySet())
        {
            if (entry.getKey() == null || entry.getKey().isBlank())
            {
                return "attributes 含空属性名";
            }
            if (entry.getValue() == null || entry.getValue().op == null)
            {
                return "属性 " + entry.getKey() + " 缺少 op";
            }
            String opError = entry.getValue().resolve();
            if (opError != null)
            {
                return "属性 " + entry.getKey() + "：" + opError;
            }
        }
        if (!this.meleeDamageType.isEmpty() && ResourceLocation.tryParse(this.meleeDamageType) == null)
        {
            return "melee_damage_type 不是合法 ID：" + this.meleeDamageType;
        }
        for (EffectSpec effect : this.effects)
        {
            if (effect.effect == null || effect.effect.isBlank())
            {
                return "effects 含空 effect";
            }
            if (ResourceLocation.tryParse(effect.effect) == null)
            {
                return "effects 里的 " + effect.effect + " 不是合法 ID";
            }
        }
        this.resolvedId = id;
        if (this.displayName == null || this.displayName.isBlank())
        {
            this.displayName = id;
        }
        return null;
    }

    /** 判据：写出来的条件全部成立才算命中（「或」要靠多条词条表达）。 */
    public boolean matches(PlayerProfile profile)
    {
        return this.counters != null && this.counters.matches(profile);
    }

    /** 免疫判定，供 BossAffixRuntime 用。 */
    public boolean isImmuneTo(DamageSource source)
    {
        for (String key : this.immuneTo)
        {
            if (matchesImmunity(key, source))
            {
                return true;
            }
        }
        return false;
    }

    /** 供 BossAffixRuntime 复用：免疫简写（all/projectile/fire/explosion/fall/magic/melee）或直接伤害类型 ID。 */
    public static boolean matchesImmunity(String key, DamageSource source)
    {
        switch (key.toLowerCase(Locale.ROOT))
        {
            case "all":
                return true;
            case "projectile":
                return source.is(DamageTypeTags.IS_PROJECTILE);
            case "fire":
                return source.is(DamageTypeTags.IS_FIRE);
            case "explosion":
                return source.is(DamageTypeTags.IS_EXPLOSION);
            case "fall":
                return source.is(DamageTypeTags.IS_FALL);
            case "magic":
                return source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC);
            case "melee":
                // 「直接来源是生物」——近身范围伤害也会被算作近战
                return source.getDirectEntity() instanceof LivingEntity;
            default:
                ResourceLocation parsed = ResourceLocation.tryParse(key);
                if (parsed == null)
                {
                    return false;
                }
                return source.is(ResourceKey.create(Registries.DAMAGE_TYPE, parsed));
        }
    }

    public static class CounterSpec
    {
        @SerializedName("min_armor")
        public Double minArmor;

        @SerializedName("min_armor_toughness")
        public Double minArmorToughness;

        @SerializedName("min_max_health")
        public Double minMaxHealth;

        @SerializedName("min_attack_damage")
        public Double minAttackDamage;

        @SerializedName("min_movement_speed")
        public Double minMovementSpeed;

        /** any / melee / ranged / unarmed */
        @SerializedName("weapon")
        public String weapon;

        @SerializedName("enchantments")
        public List<String> enchantments;

        @SerializedName("elytra")
        public Boolean elytra;

        @SerializedName("always")
        public Boolean always;

        public boolean matches(PlayerProfile profile)
        {
            if (Boolean.TRUE.equals(this.always))
            {
                return true;
            }
            if (this.minArmor != null && profile.armor < this.minArmor)
            {
                return false;
            }
            if (this.minArmorToughness != null && profile.armorToughness < this.minArmorToughness)
            {
                return false;
            }
            if (this.minMaxHealth != null && profile.maxHealth < this.minMaxHealth)
            {
                return false;
            }
            if (this.minAttackDamage != null && profile.attackDamage < this.minAttackDamage)
            {
                return false;
            }
            if (this.minMovementSpeed != null && profile.movementSpeed < this.minMovementSpeed)
            {
                return false;
            }
            if (this.weapon != null && !"any".equalsIgnoreCase(this.weapon)
                    && PlayerProfile.WeaponClass.parse(this.weapon) != profile.weapon)
            {
                return false;
            }
            if (this.enchantments != null && !this.enchantments.isEmpty())
            {
                boolean any = false;
                for (String enchantment : this.enchantments)
                {
                    if (profile.hasEnchantment(enchantment))
                    {
                        any = true;
                        break;
                    }
                }
                if (!any)
                {
                    return false;
                }
            }
            if (this.elytra != null && this.elytra != profile.elytra)
            {
                return false;
            }
            // 一条词条全是默认值 → 没有任何条件，视为不命中，避免误伤所有玩家（保底请显式写 always）
            return hasAnyCondition();
        }

        public boolean hasAnyCondition()
        {
            return this.minArmor != null || this.minArmorToughness != null || this.minMaxHealth != null
                    || this.minAttackDamage != null || this.minMovementSpeed != null || this.weapon != null
                    || (this.enchantments != null && !this.enchantments.isEmpty()) || this.elytra != null
                    || Boolean.TRUE.equals(this.always);
        }
    }

    public static class AttributeMod
    {
        @SerializedName("op")
        public String op;

        @SerializedName("value")
        public double value;

        @Nullable
        String resolve()
        {
            switch (this.op == null ? "" : this.op.toLowerCase(Locale.ROOT))
            {
                case "add":
                case "multiply_base":
                case "multiply_total":
                case "set":
                    return null;
                default:
                    return "op 只支持 add / multiply_base / multiply_total / set（当前 " + this.op + "）";
            }
        }

        public boolean isSet()
        {
            return "set".equalsIgnoreCase(this.op);
        }
    }

    public static class EffectSpec
    {
        @SerializedName("effect")
        public String effect;

        @SerializedName("amplifier")
        public int amplifier = 0;

        /** -1 = 无限。 */
        @SerializedName("duration")
        public int duration = -1;
    }
}
