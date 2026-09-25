package com.trialportal.registry;

import com.trialportal.TrialPortal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 三个传送门的 BlockItem，只用于放置，无特殊行为。 */
public final class ModItems
{
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TrialPortal.MODID);

    public static final RegistryObject<BlockItem> CHALLENGE_PORTAL = ITEMS.register("challenge_portal",
            () -> new BlockItem(ModBlocks.CHALLENGE_PORTAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> TOWER_PORTAL = ITEMS.register("tower_portal",
            () -> new BlockItem(ModBlocks.TOWER_PORTAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> TRIAL_PORTAL = ITEMS.register("trial_portal",
            () -> new BlockItem(ModBlocks.TRIAL_PORTAL.get(), new Item.Properties()));
}
