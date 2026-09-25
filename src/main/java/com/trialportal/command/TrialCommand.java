package com.trialportal.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.boss.PlayerProfile;
import com.trialportal.block.entity.TrialPortalBlockEntity;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialLibrary;
import com.trialportal.trial.TrialManager;
import com.trialportal.trial.TrialMode;
import com.trialportal.trial.TrialSession;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * /tei 指令树（权限等级 2）。调数据包时最顺手的流程：
 * profile 看判据命中 → 改 JSON → /reload → start 直接试。
 */
public final class TrialCommand
{
    private TrialCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("tei")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("trial")
                        .then(Commands.literal("list").executes(TrialCommand::list))
                        .then(Commands.literal("start")
                                .then(Commands.argument("trial", ResourceLocationArgument.id())
                                        .executes(TrialCommand::start)))
                        .then(Commands.literal("stop").executes(TrialCommand::stop))
                        .then(Commands.literal("status").executes(TrialCommand::status))
                        .then(Commands.literal("bind")
                                .then(Commands.argument("trial", ResourceLocationArgument.id())
                                        .executes(TrialCommand::bind)))
                        .then(Commands.literal("floor")
                                .executes(TrialCommand::showFloor)
                                .then(Commands.argument("floor", IntegerArgumentType.integer(0))
                                        .executes(TrialCommand::setFloor)))
                        .then(Commands.literal("profile").executes(TrialCommand::profile))
                        .then(Commands.literal("affixes").executes(TrialCommand::affixes))
                        .then(Commands.literal("enter").executes(TrialCommand::enter))
                        .then(Commands.literal("home").executes(TrialCommand::home)))
                .then(Commands.literal("arena")
                        .then(Commands.literal("info").executes(TrialCommand::arenaInfo))));
    }

    private static int list(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal("已加载 " + TrialLibrary.ids().size() + " 份试炼定义：")
                .withStyle(ChatFormatting.GOLD), false);
        for (String id : TrialLibrary.ids())
        {
            TrialDefinition definition = TrialLibrary.get(id);
            if (definition == null)
            {
                continue;
            }
            int waves = definition.finiteWaveCount();
            source.sendSuccess(() -> Component.literal("  " + id + " · " + definition.displayName
                            + "（" + definition.mode.jsonKey() + "，" + (waves < 0 ? "无尽" : waves + " 波") + "）")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return TrialLibrary.ids().size();
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(context, "trial");
        TrialDefinition definition = TrialLibrary.get(id.toString());
        if (definition == null)
        {
            context.getSource().sendFailure(Component.literal("找不到试炼 " + id + "（指令要求写全命名空间，如 trial_portal:challenge）"));
            return 0;
        }
        // 指令开局与大厅点门等价：分配隔间、建平台、把玩家送进去
        return com.trialportal.arena.ArenaService.beginSession(player, definition) ? 1 : 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TrialManager.get(context.getSource().getServer()).stop(player);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TrialManager manager = TrialManager.get(context.getSource().getServer());
        TrialSession session = manager.sessionOf(player.getUUID());
        if (session == null)
        {
            context.getSource().sendSuccess(() -> Component.literal("当前没有进行中的试炼").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        TrialMode mode = definition == null ? TrialMode.CHALLENGE : definition.mode;
        String modeKey = mode.jsonKey();
        int floor = manager.recordedFloor(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal(
                session.definitionId + "（" + modeKey + "） · " + session.describe(mode))
                .withStyle(ChatFormatting.AQUA), false);
        context.getSource().sendSuccess(() -> Component.literal("爬塔记录层数：" + floor
                + " · 场地隔间 " + session.cell
                + (com.trialportal.arena.ArenaService.isArena(session.dimension) ? "（在试炼场地维度）" : "（就地，旧会话）"))
                .withStyle(ChatFormatting.GRAY), false);
        if (!session.bossAffixes.isEmpty())
        {
            context.getSource().sendSuccess(() -> Component.literal("BOSS 词条：" + String.join("、", session.bossAffixes))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        return 1;
    }

    private static int bind(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(context, "trial");
        if (TrialLibrary.get(id.toString()) == null)
        {
            context.getSource().sendFailure(Component.literal("找不到试炼 " + id));
            return 0;
        }
        HitResult hit = player.pick(8.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK)
        {
            context.getSource().sendFailure(Component.literal("8 格内没有对着方块"));
            return 0;
        }
        BlockPos pos = blockHit.getBlockPos();
        if (!(player.level().getBlockEntity(pos) instanceof TrialPortalBlockEntity blockEntity))
        {
            context.getSource().sendFailure(Component.literal("对着的不是传送门方块"));
            return 0;
        }
        blockEntity.bind(id.toString());
        context.getSource().sendSuccess(() -> Component.literal("已把 " + pos.toShortString() + " 绑定到 " + id)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int showFloor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int floor = TrialManager.get(context.getSource().getServer()).recordedFloor(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal("当前爬塔记录：" + floor + " 层").withStyle(ChatFormatting.AQUA), false);
        return floor;
    }

    private static int setFloor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int floor = IntegerArgumentType.getInteger(context, "floor");
        TrialManager.get(context.getSource().getServer()).setRecordedFloor(player.getUUID(), floor);
        context.getSource().sendSuccess(() -> Component.literal("爬塔记录已设为 " + floor + " 层").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int enter(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return com.trialportal.arena.ArenaService.enterLobby(context.getSource().getPlayerOrException()) ? 1 : 0;
    }

    private static int home(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (context.getSource().getServer() != null
                && TrialManager.get(context.getSource().getServer()).hasSession(player.getUUID()))
        {
            context.getSource().sendFailure(Component.literal("还有进行中的试炼：先用潜行右键结算，或用 /tei trial stop 中止"));
            return 0;
        }
        com.trialportal.arena.ArenaService.returnHome(player);
        return 1;
    }

    private static int arenaInfo(CommandContext<CommandSourceStack> context)
    {
        var server = context.getSource().getServer();
        var arena = com.trialportal.arena.ArenaService.level(server);
        int radius = com.trialportal.arena.ArenaService.radius();
        context.getSource().sendSuccess(() -> Component.literal(
                "场地维度 " + com.trialportal.arena.ArenaService.ARENA.location()
                        + (arena == null ? "：未加载" : "：已加载")
                        + " · 平台半径 " + radius
                        + " · 隔间间隔 " + com.trialportal.config.TrialConfig.ARENA_CELL_SPACING.get()
                        + " · 占用中的隔间 " + com.trialportal.arena.ArenaService.usedCellCount())
                .withStyle(arena == null ? ChatFormatting.RED : ChatFormatting.AQUA), false);
        return arena == null ? 0 : 1;
    }

    private static int profile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerProfile profile = PlayerProfile.of(player);
        context.getSource().sendSuccess(() -> Component.literal("属性画像：" + profile.describe()).withStyle(ChatFormatting.AQUA), false);
        if (!profile.mainhandEnchantments.isEmpty())
        {
            context.getSource().sendSuccess(() -> Component.literal("主手附魔：" + String.join("、", profile.mainhandEnchantments))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        var matched = BossAffixLibrary.matchingFor(profile);
        context.getSource().sendSuccess(() -> Component.literal("会被这些词条命中：" + (matched.isEmpty() ? "（无）" : String.join("、", matched)))
                .withStyle(matched.isEmpty() ? ChatFormatting.RED : ChatFormatting.LIGHT_PURPLE), false);
        return matched.size();
    }

    private static int affixes(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal("已加载 " + BossAffixLibrary.ids().size() + " 条 BOSS 词条：")
                .withStyle(ChatFormatting.GOLD), false);
        for (String id : BossAffixLibrary.ids())
        {
            BossAffix affix = BossAffixLibrary.get(id);
            if (affix == null)
            {
                continue;
            }
            source.sendSuccess(() -> Component.literal("  " + id + " · " + affix.displayNameOrDefault()
                            + "（weight=" + affix.weight + "）")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return BossAffixLibrary.ids().size();
    }
}
