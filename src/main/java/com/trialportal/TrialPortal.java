package com.trialportal;

import com.mojang.logging.LogUtils;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.config.TrialConfig;
import com.trialportal.data.JsonReloadListener;
import com.trialportal.registry.ModBlocks;
import com.trialportal.registry.ModItems;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialLibrary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(TrialPortal.MODID)
public class TrialPortal
{
    public static final String MODID = "trial_portal";

    private static final Logger LOGGER = LogUtils.getLogger();

    public TrialPortal()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TrialConfig.SPEC);

        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
    }

    /** 内容全部走数据包：data/&lt;命名空间&gt;/trials/ 与 data/&lt;命名空间&gt;/boss_affixes/，/reload 即可生效。 */
    private void onAddReloadListeners(AddReloadListenerEvent event)
    {
        event.addListener(new JsonReloadListener<>("trials", TrialDefinition.class, "试炼",
                (id, definition) -> definition.resolve(id), TrialLibrary::replace));
        event.addListener(new JsonReloadListener<>("boss_affixes", BossAffix.class, "BOSS 词条",
                (id, affix) -> affix.resolve(id), BossAffixLibrary::replace));
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("[trial_portal] 三种玩法框架已加载：内容来自数据包 trials / boss_affixes 两个目录");
    }
}
