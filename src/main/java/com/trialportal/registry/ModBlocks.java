package com.trialportal.registry;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import com.trialportal.TrialPortal;

public final class ModBlocks
{
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, TrialPortal.MODID);

    // TODO 实现时在这里注册 challenge_portal / tower_portal / trial_portal 三个方块。
}
