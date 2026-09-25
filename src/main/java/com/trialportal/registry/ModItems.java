package com.trialportal.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import com.trialportal.TrialPortal;

public final class ModItems
{
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TrialPortal.MODID);

    // TODO 实现时在这里注册三个方块的 BlockItem。
}
