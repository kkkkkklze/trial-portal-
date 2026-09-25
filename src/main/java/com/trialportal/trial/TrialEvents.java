package com.trialportal.trial;

import com.trialportal.TrialPortal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
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
        TrialManager.get(event.getServer()).tick(event.getServer());
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
}
