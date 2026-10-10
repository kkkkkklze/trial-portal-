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
        // -Dtrial_portal.mobCensus=true 时顺手出一份注册生物普查（给整合包配内容用的取数通道）
        MobCensus.runIfRequested(event.getServer());
    }

    /**
     * 客户端进世界时看 {@code mob-census.request} 标记。
     * 走这条路是因为测试用的整合包常在别人的服务端上玩，玩家拿不到 OP，
     * 用指令通道就得改服务端；标记文件只落在客户端自己的游戏目录里。
     */
    @SubscribeEvent
    public static void onLevelLoad(net.minecraftforge.event.level.LevelEvent.Load event)
    {
        MobCensus.respondToRequest(event.getLevel());
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

    /**
     * 手里拿着可放置方块时，右键自家功能方块必须仍然生效。
     * <p>
     * 原版的顺序是"先把手里的物品对目标点用一次"（{@code Item#useOn} 才会去放方块），
     * 放置被上面那条禁令取消后，这一下就被算成"已经消费掉了"，{@code Block#use} 根本轮不到执行——
     * 玩家看到的是"我点了守塔之枢，什么也没发生"（真机撞到：手里拿着别的 mod 的战利品方块时点不动塔，
     * 换成指南针之类非方块物品就正常）。Forge 的 {@code PlayerInteractEvent.RightClickBlock} 正是为此
     * 提供两个独立开关（javadoc：本事件控制 {@code onItemUseFirst}/{@code Block#use}/{@code Item#useOn}
     * 谁被调用；取消整个事件则三者都不调用），所以对功能方块只拒"用手里的物品"、放行"用这个方块"。
     * 两侧都要设，客户端服务端不一致会不同步。
     */
    @SubscribeEvent
    public static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event)
    {
        net.minecraft.world.level.Level level = event.getLevel();
        if (!ArenaService.isArena(level.dimension()))
        {
            return;
        }
        if (keepsBlockUse(level.getBlockState(event.getPos())))
        {
            event.setUseItem(net.minecraftforge.event.entity.player.PlayerInteractEvent.Result.DENY);
        }
    }

    /** 哪些方块"宁可不让手里的物品出手，也要把右键留给方块自己"——只有自家三种功能终端。 */
    static boolean keepsBlockUse(net.minecraft.world.level.block.state.BlockState state)
    {
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof com.trialportal.block.TrialPortalBlock
                || block instanceof com.trialportal.block.ArenaExitBlock
                || block instanceof com.trialportal.block.WardPylonBlock;
    }
}
