package com.trialportal;

import com.mojang.logging.LogUtils;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.arena.RoomLibrary;
import com.trialportal.command.TrialCommand;
import com.trialportal.config.TrialConfig;
import com.trialportal.data.JsonReloadListener;
import com.trialportal.registry.ModBlockEntities;
import com.trialportal.registry.ModBlocks;
import com.trialportal.registry.ModCreativeTabs;
import com.trialportal.registry.ModItems;
import com.trialportal.trial.ChallengeDefinition;
import com.trialportal.trial.DefenseDefinition;
import com.trialportal.trial.DefenseLibrary;
import com.trialportal.trial.ChallengeLibrary;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialLibrary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
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
        com.trialportal.network.TrialNetwork.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TrialConfig.SPEC);

        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
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
        event.addListener(new JsonReloadListener<>("challenges", ChallengeDefinition.class, "资源副本",
                (id, challenge) -> challenge.resolve(id), ChallengeLibrary::replace));
        event.addListener(new JsonReloadListener<>("defenses", DefenseDefinition.class, "守塔",
                (id, defense) -> defense.resolve(id), DefenseLibrary::replace));
    }

    private void onRegisterCommands(RegisterCommandsEvent event)
    {
        TrialCommand.register(event.getDispatcher());
    }

    /**
     * 「手里拿着东西点不动我们的方块」这一条由 {@code TrialEvents.onRightClickBlock} 统一处理。
     * 这里以前有一份按标记接口（{@code block/SneakInteractBlock}）筛的孪生实现：两处判据、两份覆盖面，
     * 结果新进生存的往返门只被其中一处认到（野外那一半没人管）。同一件事只留一条链，接口随之删掉。
     */

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("[trial_portal] 三种玩法框架已加载：内容来自数据包 trials / boss_affixes 两个目录");
    }
}
