package com.trialportal.registry;

import com.trialportal.TrialPortal;
import com.trialportal.block.TrialPortalBlock;
import com.trialportal.trial.TrialMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks
{
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, TrialPortal.MODID);

    public static final RegistryObject<TrialPortalBlock> CHALLENGE_PORTAL =
            BLOCKS.register("challenge_portal", () -> new TrialPortalBlock(TrialMode.CHALLENGE, properties(MapColor.COLOR_CYAN)));

    public static final RegistryObject<TrialPortalBlock> TOWER_PORTAL =
            BLOCKS.register("tower_portal", () -> new TrialPortalBlock(TrialMode.TOWER, properties(MapColor.COLOR_PURPLE)));

    public static final RegistryObject<TrialPortalBlock> TRIAL_PORTAL =
            BLOCKS.register("trial_portal", () -> new TrialPortalBlock(TrialMode.TRIAL, properties(MapColor.COLOR_BLUE)));

    private static BlockBehaviour.Properties properties(MapColor color)
    {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(6.0F, 1200.0F)
                .sound(SoundType.AMETHYST)
                .lightLevel(state -> 7)
                .noOcclusion();
    }
}
