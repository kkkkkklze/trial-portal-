package com.trialportal.registry;

import com.trialportal.TrialPortal;
import com.trialportal.block.ArenaExitBlock;
import com.trialportal.block.TrialPortalBlock;
import com.trialportal.block.WardPylonBlock;
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

    /**
     * 守塔那座塔：大厅里的是入口、隔间里的是被守目标。
     * 按裁定**没有合成配方**（§9 第 11 条④），只由场地程序化摆放 + 创造栏拿得到。
     */
    public static final RegistryObject<WardPylonBlock> WARD_PYLON =
            BLOCKS.register("ward_pylon", () -> new WardPylonBlock(properties(MapColor.COLOR_LIGHT_BLUE)));

    /**
     * 大厅与野外之间唯一的通道：野外点它进大厅，场地里点它回家（§9 第 12 条）。
     * 三个玩法门按裁定退回创造栏，进出都只走这一台，所以它要能在生存里合出来、在野外拆得掉。
     * 场地里那几台由「场地禁止破坏」挡着，不会被拆走。
     */
    public static final RegistryObject<ArenaExitBlock> ARENA_EXIT =
            BLOCKS.register("arena_exit", () -> new ArenaExitBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(6.0F, 1200.0F)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 4)
                    .noOcclusion()));

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
