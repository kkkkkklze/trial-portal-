package com.trialportal;

import com.mojang.logging.LogUtils;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.arena.RoomLibrary;
import com.trialportal.block.SneakInteractBlock;
import com.trialportal.command.TrialCommand;
import com.trialportal.config.TrialConfig;
import com.trialportal.data.JsonReloadListener;
import com.trialportal.registry.ModBlockEntities;
import com.trialportal.registry.ModBlocks;
import com.trialportal.registry.ModCreativeTabs;
import com.trialportal.registry.ModItems;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialLibrary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
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

    public static final Logger LOGGER = LogUtils.getLogger();

    public TrialPortal()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TrialConfig.SPEC);

        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onRightClickBlock);
    }

    /** 内容全部走数据包：data/&lt;命名空间&gt;/trials/ 与 data/&lt;命名空间&gt;/boss_affixes/，/reload 即可生效。 */
    private void onAddReloadListeners(AddReloadListenerEvent event)
    {
        event.addListener(new JsonReloadListener<>("trials", TrialDefinition.class, "试炼",
                (id, definition) -> definition.resolve(id), TrialLibrary::replace));
        event.addListener(new JsonReloadListener<>("boss_affixes", BossAffix.class, "BOSS 词条",
                (id, affix) -> affix.resolve(id), BossAffixLibrary::replace));
        event.addListener(new JsonReloadListener<>("arena_rooms", RoomDefinition.class, "房间",
                (id, room) -> room.resolve(id), RoomLibrary::replace));
    }

    private void onRegisterCommands(RegisterCommandsEvent event)
    {
        TrialCommand.register(event.getDispatcher());
    }

    /**
     * 原版在「潜行 + 手持非空物品」右键方块时会跳过 BlockState#use()，而本模组的结算恰恰是潜行右键。
     * 这里为实现了 {@link SneakInteractBlock} 的方块强制放行方块使用、并压掉物品使用。
     */
    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof SneakInteractBlock))
        {
            return;
        }
        event.setUseBlock(Event.Result.ALLOW);
        if (event.getEntity().isShiftKeyDown() && !event.getItemStack().isEmpty())
        {
            event.setUseItem(Event.Result.DENY);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("[trial_portal] 三种玩法框架已加载：内容来自数据包 trials / boss_affixes 两个目录");
    }
}
