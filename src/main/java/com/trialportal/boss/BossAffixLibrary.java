package com.trialportal.boss;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * BOSS 词条库：加载、按玩家画像抽样、把词条应用到 BOSS 身上。
 */
public final class BossAffixLibrary
{
    private static final Logger LOGGER = LogUtils.getLogger();

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

    // -------------------------------------------------------------- 应用到 BOSS

    /** 实体持久化数据里记录「免疫列表」「近战伤害类型替换」「已贴词条」，随实体存档。 */
    public static final String TAG_IMMUNITY = "trial_portal_immune";
    public static final String TAG_MELEE_TYPE = "trial_portal_melee_damage_type";
    public static final String TAG_AFFIXES = "trial_portal_affixes";

    /**
     * 把抽中的词条贴到 BOSS 身上。
     * <p>
     * 属性修改一律走 baseValue 或 permanent modifier：{@code AttributeInstance#save()} 只写
     * permanent modifiers，瞬态修饰符（addTransientModifier）在区块卸载重载后会丢，
     * 所以这里刻意不用它——同一只 BOSS 走远再回来不该变弱。
     */
    public static void applyTo(Mob mob, List<BossAffix> affixes)
    {
        if (affixes.isEmpty())
        {
            return;
        }
        CompoundTag persistent = mob.getPersistentData();
        List<String> immune = new ArrayList<>(splitList(persistent.getString(TAG_IMMUNITY)));
        List<String> applied = new ArrayList<>(splitList(persistent.getString(TAG_AFFIXES)));

        for (BossAffix affix : affixes)
        {
            for (Map.Entry<String, BossAffix.AttributeMod> entry : affix.attributes.entrySet())
            {
                applyAttribute(mob, affix, entry.getKey(), entry.getValue());
            }
            immune.addAll(affix.immuneTo);
            if (affix.meleeDamageType != null && !affix.meleeDamageType.isBlank())
            {
                persistent.putString(TAG_MELEE_TYPE, affix.meleeDamageType);
            }
            for (BossAffix.EffectSpec effect : affix.effects)
            {
                MobEffect instance = ForgeRegistries.MOB_EFFECTS.getValue(parse(effect.effect));
                if (instance == null)
                {
                    LOGGER.warn("[trial_portal] 词条 {} 的效果 {} 不存在，已跳过", affix.id(), effect.effect);
                    continue;
                }
                int duration = effect.duration < 0 ? MobEffectInstance.INFINITE_DURATION : effect.duration;
                mob.addEffect(new MobEffectInstance(instance, duration, effect.amplifier, false, true));
            }
            applyEquipment(mob, affix);
            if (!applied.contains(affix.id()))
            {
                applied.add(affix.id());
            }
        }
        persistent.putString(TAG_IMMUNITY, String.join(",", immune));
        persistent.putString(TAG_AFFIXES, String.join(",", applied));
        mob.setHealth(mob.getMaxHealth());
    }

    private static void applyAttribute(Mob mob, BossAffix affix, String rawAttribute, BossAffix.AttributeMod mod)
    {
        Attribute attribute = resolveAttribute(rawAttribute);
        if (attribute == null)
        {
            LOGGER.warn("[trial_portal] 词条 {} 的属性 {} 不存在，已跳过", affix.id(), rawAttribute);
            return;
        }
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null)
        {
            return;
        }
        if (mod.isSet())
        {
            instance.setBaseValue(mod.value);
            return;
        }
        AttributeModifier.Operation operation = switch (mod.op.toLowerCase(Locale.ROOT))
        {
            case "multiply_base" -> AttributeModifier.Operation.MULTIPLY_BASE;
            case "multiply_total" -> AttributeModifier.Operation.MULTIPLY_TOTAL;
            default -> AttributeModifier.Operation.ADDITION;
        };
        String name = "trial_portal:" + affix.id() + ":" + rawAttribute;
        // 名字派生的固定 UUID：同一词条同一属性重复应用只会有一份修饰符，不会叠层
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        if (instance.getModifier(uuid) == null)
        {
            instance.addPermanentModifier(new AttributeModifier(uuid, name, mod.value, operation));
        }
    }

    /** 1.20.1 的属性注册名带 generic. 前缀；写成 1.21 风格的 minecraft:max_health 也接受。 */
    @Nullable
    public static Attribute resolveAttribute(String raw)
    {
        ResourceLocation direct = parse(raw);
        if (direct == null)
        {
            return null;
        }
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(direct);
        if (attribute != null || direct.getNamespace().equals("minecraft") && direct.getPath().contains("."))
        {
            return attribute;
        }
        return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(direct.getNamespace(), "generic." + direct.getPath()));
    }

    private static void applyEquipment(Mob mob, BossAffix affix)
    {
        for (Map.Entry<String, String> entry : affix.equipment.entrySet())
        {
            EquipmentSlot slot = parseSlot(entry.getKey());
            Item item = entry.getValue() == null ? null : ForgeRegistries.ITEMS.getValue(parse(entry.getValue()));
            if (slot == null || item == null)
            {
                LOGGER.warn("[trial_portal] 词条 {} 的装备 {}.{} 无法解析，已跳过", affix.id(), entry.getKey(), entry.getValue());
                continue;
            }
            ItemStack stack = new ItemStack(item);
            List<String> enchantments = affix.equipmentEnchantments.get(entry.getKey());
            if (enchantments != null)
            {
                for (String raw : enchantments)
                {
                    int colon = raw.lastIndexOf(':');
                    String enchantmentId = colon < 0 ? raw : raw.substring(0, colon);
                    int level = 1;
                    if (colon >= 0)
                    {
                        try
                        {
                            level = Integer.parseInt(raw.substring(colon + 1));
                        }
                        catch (NumberFormatException ignored)
                        {
                            LOGGER.warn("[trial_portal] 词条 {} 的附魔等级非法：{}", affix.id(), raw);
                            continue;
                        }
                    }
                    Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(parse(enchantmentId));
                    if (enchantment == null)
                    {
                        LOGGER.warn("[trial_portal] 词条 {} 的附魔 {} 不存在，已跳过", affix.id(), enchantmentId);
                        continue;
                    }
                    stack.enchant(enchantment, Math.max(1, level));
                }
            }
            mob.setItemSlot(slot, stack);
            mob.setDropChance(slot, 0.0F); // 装备不掉，不会变成免费装备
        }
    }

    @Nullable
    public static EquipmentSlot parseSlot(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT))
        {
            case "mainhand", "main_hand" -> EquipmentSlot.MAINHAND;
            case "offhand", "off_hand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    /** 名字后缀与颜色：在 applyTo 之后调用，叠在已有的自定义名后面。 */
    public static void decorate(Mob mob, List<BossAffix> affixes)
    {
        Component base = mob.getCustomName();
        if (base == null)
        {
            base = Component.empty();
        }
        MutableComponent name = base.copy();
        TextColor color = null;
        for (BossAffix affix : affixes)
        {
            if (affix.nameSuffix != null && !affix.nameSuffix.isBlank())
            {
                name.append(affix.nameSuffix);
            }
            TextColor parsed = parseColor(affix.nameColor);
            if (parsed != null)
            {
                color = parsed;
            }
        }
        if (color != null)
        {
            TextColor tint = color;
            name.withStyle(style -> style.withColor(tint));
        }
        mob.setCustomName(name);
        mob.setCustomNameVisible(true);
    }

    /** name_color 是 6 位十六进制（如 FF5555）；解析不了就当没写。 */
    @Nullable
    private static TextColor parseColor(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            return TextColor.fromRgb(Integer.parseInt(raw.trim(), 16));
        }
        catch (NumberFormatException exception)
        {
            LOGGER.warn("[trial_portal] name_color 不是 6 位十六进制：{}", raw);
            return null;
        }
    }

    public static List<String> splitList(String joined)
    {
        List<String> result = new ArrayList<>();
        if (joined == null || joined.isBlank())
        {
            return result;
        }
        for (String part : joined.split(","))
        {
            if (!part.isBlank())
            {
                result.add(part.trim());
            }
        }
        return result;
    }
}
