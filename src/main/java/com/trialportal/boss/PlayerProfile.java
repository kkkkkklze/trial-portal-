package com.trialportal.boss;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 开局瞬间采集的玩家属性快照。词条判据只读这个快照，不直接读玩家——
 * 否则一场试炼打到一半玩家换了装备，BOSS 的克制依据就跟着漂了。
 */
public class PlayerProfile
{
    public enum WeaponClass
    {
        UNARMED,
        RANGED,
        MELEE,
        OTHER;

        public static WeaponClass parse(String raw)
        {
            if (raw == null)
            {
                return OTHER;
            }
            switch (raw.toLowerCase(Locale.ROOT))
            {
                case "unarmed":
                    return UNARMED;
                case "ranged":
                    return RANGED;
                case "melee":
                    return MELEE;
                default:
                    return OTHER;
            }
        }
    }

    public final double armor;
    public final double armorToughness;
    public final double maxHealth;
    public final double attackDamage;
    public final double movementSpeed;
    public final WeaponClass weapon;
    public final Set<String> mainhandEnchantments;
    public final boolean elytra;

    private PlayerProfile(double armor, double armorToughness, double maxHealth, double attackDamage,
                          double movementSpeed, WeaponClass weapon, Set<String> mainhandEnchantments, boolean elytra)
    {
        this.armor = armor;
        this.armorToughness = armorToughness;
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.movementSpeed = movementSpeed;
        this.weapon = weapon;
        this.mainhandEnchantments = Collections.unmodifiableSet(mainhandEnchantments);
        this.elytra = elytra;
    }

    public static PlayerProfile of(Player player)
    {
        ItemStack mainhand = player.getMainHandItem();
        Set<String> enchants = new LinkedHashSet<>();
        if (!mainhand.isEmpty())
        {
            mainhand.getAllEnchantments().forEach((enchantment, level) ->
            {
                ResourceLocation key = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
                if (key != null && level != null && level > 0)
                {
                    enchants.add(key.toString());
                }
            });
        }
        return new PlayerProfile(
                player.getArmorValue(),
                player.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                player.getMaxHealth(),
                player.getAttributeValue(Attributes.ATTACK_DAMAGE),
                player.getAttributeValue(Attributes.MOVEMENT_SPEED),
                classify(mainhand),
                enchants,
                player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
        );
    }

    private static WeaponClass classify(ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return WeaponClass.UNARMED;
        }
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem)
        {
            return WeaponClass.RANGED;
        }
        if (stack.getItem() instanceof SwordItem || stack.is(ItemTags.AXES))
        {
            return WeaponClass.MELEE;
        }
        return WeaponClass.OTHER;
    }

    public boolean hasEnchantment(String id)
    {
        return this.mainhandEnchantments.contains(id);
    }

    /** 给 /tei trial profile 用的一行摘要。 */
    public String describe()
    {
        return String.format(Locale.ROOT, "护甲 %.1f / 韧性 %.1f / 生命 %.1f / 攻击 %.1f / 移速 %.3f / 武器 %s / 鞘翅 %s",
                this.armor, this.armorToughness, this.maxHealth, this.attackDamage, this.movementSpeed,
                this.weapon.name().toLowerCase(Locale.ROOT), this.elytra ? "是" : "否");
    }
}
