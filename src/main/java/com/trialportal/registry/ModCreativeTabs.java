package com.trialportal.registry;

import com.trialportal.TrialPortal;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs
{
    private ModCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TrialPortal.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.trial_portal.main"))
            .icon(() -> new ItemStack(ModItems.CHALLENGE_PORTAL.get()))
            .displayItems((parameters, output) ->
            {
                output.accept(new ItemStack(ModItems.CHALLENGE_PORTAL.get()));
                output.accept(new ItemStack(ModItems.TOWER_PORTAL.get()));
                output.accept(new ItemStack(ModItems.TRIAL_PORTAL.get()));
            })
            .build());
}
