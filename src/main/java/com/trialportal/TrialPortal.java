package com.trialportal;

import com.mojang.logging.LogUtils;
import com.trialportal.registry.ModBlocks;
import com.trialportal.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
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

        // TODO 实现时在这里挂 AddReloadListenerEvent（trials / boss_affixes 两个 JSON 加载器）
        //      与 RegisterCommandsEvent（/tei 指令树）。
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("[trial_portal] 工程骨架已加载，试炼内容尚未实现");
    }
}
