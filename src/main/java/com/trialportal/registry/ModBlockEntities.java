package com.trialportal.registry;

import com.trialportal.TrialPortal;
import com.trialportal.block.entity.TrialPortalBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 三个传送门方块共用同一个方块实体类型（Builder.of 支持多方块）。 */
public final class ModBlockEntities
{
    private ModBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TrialPortal.MODID);

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<TrialPortalBlockEntity>> TRIAL_PORTAL =
            BLOCK_ENTITIES.register("trial_portal", () -> BlockEntityType.Builder.of(
                    TrialPortalBlockEntity::new,
                    ModBlocks.CHALLENGE_PORTAL.get(),
                    ModBlocks.TOWER_PORTAL.get(),
                    ModBlocks.TRIAL_PORTAL.get()).build(null));
}
