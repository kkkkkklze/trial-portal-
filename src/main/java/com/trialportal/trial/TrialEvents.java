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
import org.jetbrains.annotations.Nullable;

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
        // 恶意联动的落笔放在玩法 tick 之后：怪是在上面那一跳（服务端 tick 的末尾）生成的，
        // 它要等下一个 tick 的实体 tick 才被它那侧初始化好——所以队列会留一拍，那是设计而不是漏。
        MaliceLink.tick();
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
     * <p>
     * 这里<b>不按维度筛</b>：往返门与玩法门现在都能出现在野外，野外那一半和场地里那一半是同一条契约。
     * 以前这条判据只挂在场地维度上，于是"昨天刚给往返门加了配方、今天它在野外手持方块时就点不动"——
     * 修了场地那一半不等于修了这个方块。
     */
    @SubscribeEvent
    public static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event)
    {
        RightClickPlan plan = planFor(event.getLevel().getBlockState(event.getPos()));
        if (plan.denyItem())
        {
            event.setUseItem(net.minecraftforge.event.entity.player.PlayerInteractEvent.Result.DENY);
        }
        if (plan.allowBlock())
        {
            event.setUseBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.Result.ALLOW);
        }
    }

    /** 右键落在一个方块上时，Forge 那两个独立开关各要怎么设。{@code NONE} = 完全不插手。 */
    public record RightClickPlan(boolean denyItem, boolean allowBlock)
    {
        static final RightClickPlan NONE = new RightClickPlan(false, false);
        static final RightClickPlan OURS = new RightClickPlan(true, true);
    }

    /**
     * 判定只吃方块状态——不吃维度。这正是这条修复的关键：往返门与玩法门现在两边都会出现，
     * 上一版把判据挂在"在场地维度里"这一条上，于是野外的往返门没人管（手持方块就点不动它）。
     */
    static RightClickPlan planFor(net.minecraft.world.level.block.state.BlockState state)
    {
        return keepsBlockUse(state) ? RightClickPlan.OURS : RightClickPlan.NONE;
    }

    /** 哪些方块"宁可不让手里的物品出手，也要把右键留给方块自己"——只有自家四种功能终端。 */
    static boolean keepsBlockUse(net.minecraft.world.level.block.state.BlockState state)
    {
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof com.trialportal.block.TrialPortalBlock
                || block instanceof com.trialportal.block.ArenaExitBlock
                || block instanceof com.trialportal.block.WardPylonBlock;
    }

    /**
     * 交互必须由真人发起。发射器、机器人、各类 {@code FakePlayer} 走的是同一套事件，
     * 而这三类方块的效果是把<b>人传走</b>或替人开局——自动机不该有这种能力（也会被用来无限撑大回返点表）。
     */
    public static boolean playerInitiated(@Nullable net.minecraft.world.entity.player.Player player)
    {
        return player != null && !(player instanceof net.minecraftforge.common.util.FakePlayer);
    }

    /**
     * 停服必须清空待拆隔间的队列：它是进程级 static，换存档重开时上一局的坐标还在，
     * 于是新档里同 UUID 的玩家会在旧坐标上被扫怪 / 解强制加载 / 释放编号——留下一间没人认领的孤儿平台。
     */
    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event)
    {
        ArenaService.clearPendingReleases();
        // 恶意那条队列按 UUID 存着活的实体引用，关服时清空，别留到下一局
        MaliceLink.clearPending();
    }
}
