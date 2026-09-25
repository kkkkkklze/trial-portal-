package com.trialportal.boss;

import com.trialportal.TrialPortal;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 词条的两个运行期生效点：伤害免疫、近战伤害类型替换。
 * 两项都读实体的 {@code getPersistentData()}（Forge 的 ForgeData 标签，随实体存档），
 * 所以区块卸载重载之后依然有效。
 */
@Mod.EventBusSubscriber(modid = TrialPortal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossAffixRuntime
{
    private BossAffixRuntime() {}

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event)
    {
        CompoundTag data = event.getEntity().getPersistentData();
        if (!data.contains(BossAffixLibrary.TAG_IMMUNITY))
        {
            return;
        }
        DamageSource source = event.getSource();
        for (String key : BossAffixLibrary.splitList(data.getString(BossAffixLibrary.TAG_IMMUNITY)))
        {
            if (BossAffix.matchesImmunity(key, source))
            {
                event.setCanceled(true); // 硬取消：连击退、音效、受伤动画都没有，也不解除已施加的效果
                return;
            }
        }
    }

    /**
     * 把 BOSS 的近战伤害换成指定类型（示例词条 anti_armor 换成 magic 用来无视护甲）。
     * 换类型会重走一遍受伤流程，因此其它模组的「魔法抗性 / 魔法免疫」会跟着生效，减伤也会重算一次。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event)
    {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();
        if (attacker == null || attacker == target || target.level().isClientSide)
        {
            return;
        }
        // 只处理这只怪「自己撞上去」的近战伤害：弹射物的直接来源是投射物，不在此列
        if (source.getDirectEntity() != attacker || !(source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK)))
        {
            return;
        }
        CompoundTag data = attacker.getPersistentData();
        if (!data.contains(BossAffixLibrary.TAG_MELEE_TYPE))
        {
            return;
        }
        ResourceLocation replacement = BossAffixLibrary.parse(data.getString(BossAffixLibrary.TAG_MELEE_TYPE));
        if (replacement == null)
        {
            return;
        }
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, replacement);
        if (source.is(key))
        {
            return; // 递归保护：替换后的伤害源不再二次处理，别删这一段
        }
        Level level = target.level();
        var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key);
        DamageSource replaced = new DamageSource(holder, source.getDirectEntity(), source.getEntity());
        float amount = event.getAmount();
        event.setCanceled(true);
        target.hurt(replaced, amount);
    }
}
