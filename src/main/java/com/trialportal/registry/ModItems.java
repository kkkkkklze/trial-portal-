package com.trialportal.registry;

import com.trialportal.TrialPortal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 自家方块的 BlockItem，只用于放置，无特殊行为。 */
public final class ModItems
{
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TrialPortal.MODID);

    /**
     * 大厅与野外之间唯一的通道。它必须有物品：配方产出、手里放置、拆掉掉落走的都是这一个物品，
     * 少了它上面三件事会一起静默失败（原版查不到物品时 {@code Block#asItem()} 给的是 AIR）。
     */
    public static final RegistryObject<BlockItem> ARENA_EXIT = ITEMS.register("arena_exit",
            () -> new BlockItem(ModBlocks.ARENA_EXIT.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> CHALLENGE_PORTAL = ITEMS.register("challenge_portal",
            () -> new BlockItem(ModBlocks.CHALLENGE_PORTAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> TOWER_PORTAL = ITEMS.register("tower_portal",
            () -> new BlockItem(ModBlocks.TOWER_PORTAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> TRIAL_PORTAL = ITEMS.register("trial_portal",
            () -> new BlockItem(ModBlocks.TRIAL_PORTAL.get(), new Item.Properties()));

    /** 守塔的塔：创造栏拿得到（结构件），但生存里没有配方——这条由自测反向断言守着。 */
    public static final RegistryObject<BlockItem> WARD_PYLON = ITEMS.register("ward_pylon",
            () -> new BlockItem(ModBlocks.WARD_PYLON.get(), new Item.Properties()));
}
