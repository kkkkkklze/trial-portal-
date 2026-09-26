package com.trialportal.trial;

import com.trialportal.TrialPortal;
import com.trialportal.arena.ArenaService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 事件转发：服务端 tick 驱动状态机，玩家死亡走各模式的失败分支。
 * 判定频率由 TrialManager 内部的 general.tick_interval 控制，这里每 tick 只做一次很便宜的转发。
 */
@Mod.EventBusSubscriber(modid = TrialPortal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrialEvents
{
    private TrialEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        MinecraftServer server = event.getServer();
        TrialManager.get(server).tick(server);
        // 先接落难的人再收孤儿隔间：反过来 tickPendingReleases 会把回返点 takeHome 走，
        // 那个人就只能被丢去世界出生点，而不是他进门前站的地方。
        ArenaService.rescueStrandedPlayers(server);
        ArenaService.tickPendingReleases(server);
    }

    /** 启动后清扫孤儿隔间：上一次运行留下的无主平台不会自己消失。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        ArenaService.sweepOrphanCells(event.getServer());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            TrialManager manager = TrialManager.get(player.server);
            if (manager.hasSession(player.getUUID()))
            {
                manager.onPlayerDeath(player);
            }
        }
    }

    /**
     * 场地维度禁止破坏方块：平台、房、走廊都是程序化生成的，玩家挖一块就会让「已建区域」与清场盒子对不上，
     * 还会留下拆不掉的空洞。给 actionbar 而不是静默取消——静默会被读成"右键/左键失灵"。
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event)
    {
        // BlockEvent#getLevel 返回 LevelAccessor，维度要看 ServerLevel
        if (event.getLevel() instanceof ServerLevel serverLevel && ArenaService.isArena(serverLevel.dimension())
                && event.getPlayer() instanceof ServerPlayer player)
        {
            event.setCanceled(true);
            TrialFeedback.actionbar(player, "试炼场地里的方块不能破坏");
        }
    }

    /** 场地维度禁止放方块：随手放一块就能垫出越界的路，也能把走廊堵死。 */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event)
    {
        if (event.getLevel() instanceof ServerLevel serverLevel && ArenaService.isArena(serverLevel.dimension())
                && event.getEntity() instanceof ServerPlayer player)
        {
            event.setCanceled(true);
            TrialFeedback.actionbar(player, "试炼场地里不能放置方块");
        }
    }
}
