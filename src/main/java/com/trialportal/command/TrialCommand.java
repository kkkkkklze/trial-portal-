package com.trialportal.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.boss.PlayerProfile;
import com.trialportal.block.entity.TrialPortalBlockEntity;
import com.trialportal.trial.ChallengeDefinition;
import com.trialportal.trial.ChallengeLibrary;
import com.trialportal.trial.DefenseDefinition;
import com.trialportal.trial.DefenseEdit;
import com.trialportal.trial.DefenseLibrary;
import com.trialportal.trial.MaliceLink;
import com.trialportal.trial.MobCensus;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
                        .then(Commands.literal("info").executes(TrialCommand::arenaInfo)))
                .then(Commands.literal("mob")
                        .then(Commands.literal("census")
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .executes(context -> MobCensus.command(context,
                                                StringArgumentType.getString(context, "category"))))
                                .executes(context -> MobCensus.command(context, null))))
                .then(Commands.literal("challenge")
                        .then(Commands.literal("list").executes(TrialCommand::challengeList))
                        .then(Commands.literal("start")
                                .then(Commands.argument("challenge", ResourceLocationArgument.id())
                                        .executes(TrialCommand::challengeStartDefault)
                                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                                .executes(TrialCommand::challengeStart)))))
                .then(Commands.literal("defense")
                        .then(Commands.literal("list").executes(TrialCommand::defenseList))
                        .then(Commands.literal("status").executes(TrialCommand::defenseStatus))
                        .then(Commands.literal("start")
                                .executes(TrialCommand::defenseStartFirst)
                                .then(Commands.argument("defense", ResourceLocationArgument.id())
                                        .executes(TrialCommand::defenseStart)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("knob", StringArgumentType.word())
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 1.0E7D))
                                                .executes(TrialCommand::defenseSet))))
                        .then(Commands.literal("wave")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("wave", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("mob", ResourceLocationArgument.id())
                                                        .executes(TrialCommand::defenseWaveAddOne)
                                                        .then(Commands.argument("count",
                                                                IntegerArgumentType.integer(1, DefenseEdit.MAX_COUNT_PER_ENTRY))
                                                                .executes(TrialCommand::defenseWaveAddCount)
                                                                .then(Commands.argument("boss", BoolArgumentType.bool())
                                                                        .executes(TrialCommand::defenseWaveAddBoss))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("wave", IntegerArgumentType.integer(0))
                                        .executes(TrialCommand::defenseWaveRemove)))))
                .then(Commands.literal("malice")
                        .then(Commands.literal("status").executes(TrialCommand::maliceStatus))
                        .then(Commands.literal("probe")
                                .executes(TrialCommand::maliceProbe)
                                .then(Commands.argument("level",
                                        IntegerArgumentType.integer(0, MaliceLink.LEVEL_CEILING))
                                        .executes(TrialCommand::maliceProbeWrite)))));
    }
    private static int challengeList(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal("已加载 " + ChallengeLibrary.ids().size() + " 份资源副本：")
                .withStyle(ChatFormatting.GOLD), false);
        for (ChallengeDefinition challenge : ChallengeLibrary.ordered())
        {
            source.sendSuccess(() -> Component.literal("  " + challenge.id() + " · " + challenge.displayNameOrDefault()
                            + " · 时限 " + challenge.durationTicks / 20 + " 秒 · 难度 "
                            + String.join("/", ChallengeDefinition.DIFFICULTY_KEYS))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return ChallengeLibrary.ids().size();
    }

    /** 不带难度参数时走默认档：与其去探测参数表（1.20.1 的 brigadier 没有 hasArgument），不如两个入口。 */
    private static int challengeStartDefault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return startChallenge(context, "normal");
    }

    private static int challengeStart(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return startChallenge(context, StringArgumentType.getString(context, "difficulty"));
    }

    private static int startChallenge(CommandContext<CommandSourceStack> context, String requested)
            throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(context, "challenge");
        ChallengeDefinition challenge = ChallengeLibrary.get(id.toString());
        if (challenge == null)
        {
            context.getSource().sendFailure(Component.literal("找不到副本 " + id));
            return 0;
        }
        String difficulty = ChallengeLibrary.normalizeDifficulty(requested);
        if (difficulty == null)
        {
            context.getSource().sendFailure(Component.literal("难度只允许 "
                    + String.join("/", ChallengeDefinition.DIFFICULTY_KEYS) + "，写的是 " + requested));
            return 0;
        }
        return com.trialportal.arena.ArenaService.beginDungeon(player, challenge, difficulty) ? 1 : 0;
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
        TrialMode mode = session.modeOf(definition);
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
                        + " · 占用中的隔间 " + com.trialportal.arena.ArenaService.usedCellCount(context.getSource().getServer()))
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

    // ---------------------------------------------------- 守塔配置口（§9 第 11 条③：创造模式可改）

    private static int defenseList(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        java.util.List<DefenseDefinition> list = DefenseLibrary.ordered();
        source.sendSuccess(() -> Component.literal("已加载 " + list.size() + " 份守塔定义：")
                .withStyle(ChatFormatting.GOLD), false);
        for (DefenseDefinition defense : list)
        {
            source.sendSuccess(() -> Component.literal("  " + defense.id() + " · " + defense.displayNameOrDefault()
                            + " · " + defense.finiteWaveCount() + " 波 · 塔血 " + defense.towerHealth
                            + " · 结界半径 " + defense.wardRadius + " · 拆塔怪比例 " + defense.towerFocusShare)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return list.size();
    }

    private static int defenseStartFirst(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        DefenseDefinition defense = DefenseLibrary.first();
        if (defense == null)
        {
            context.getSource().sendFailure(Component.literal("defenses/ 里一份守塔定义都没有，开不了局"));
            return 0;
        }
        return beginDefense(context, defense);
    }

    private static int defenseStart(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String id = ResourceLocationArgument.getId(context, "defense").toString();
        DefenseDefinition defense = DefenseLibrary.get(id);
        if (defense == null)
        {
            context.getSource().sendFailure(Component.literal("找不到守塔定义 " + id
                    + "（要写全命名空间，如 trial_portal:first_ward；/tei defense list 看有哪些）"));
            return 0;
        }
        return beginDefense(context, defense);
    }

    private static int beginDefense(CommandContext<CommandSourceStack> context, DefenseDefinition defense)
            throws CommandSyntaxException
    {
        // 指令开局与右键大厅那座塔完全等价：同一条分配隔间、铺结界、立塔、送人的链路
        return com.trialportal.arena.ArenaService.beginDefense(context.getSource().getPlayerOrException(), defense)
                ? 1 : 0;
    }

    /** 取当前这场守塔的会话；不是守塔就报清楚，返回 null。 */
    @Nullable
    private static TrialSession defenseSession(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException
    {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TrialSession session = TrialManager.get(context.getSource().getServer()).sessionOf(player.getUUID());
        if (session == null || session.liveDefense == null)
        {
            context.getSource().sendFailure(Component.literal("改参数得先有一场守塔：/tei defense start [<id>]"));
            return null;
        }
        return session;
    }

    private static int defenseStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        TrialSession session = defenseSession(context);
        if (session == null)
        {
            return 0;
        }
        DefenseDefinition defense = session.liveDefense;
        ServerPlayer player = context.getSource().getPlayerOrException();
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal(session.defenseId + " · " + session.describe(session.mode))
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("  塔血 " + towerReadout(player, session)
                        + " · 每口 " + defense.towerDamagePerHit + " 隔 " + defense.towerHitIntervalTicks + " tick"
                        + " · 拆塔怪比例 " + defense.towerFocusShare + " · 结界半径 " + defense.wardRadius)
                .withStyle(ChatFormatting.GRAY), false);
        for (int index = 0; index < defense.waves.size(); index++)
        {
            StringBuilder text = new StringBuilder();
            for (TrialDefinition.MobEntry entry : defense.waves.get(index).mobs)
            {
                if (text.length() > 0)
                {
                    text.append("，");
                }
                text.append(entry.entity).append('×').append(Math.max(1, entry.count));
                if (entry.boss)
                {
                    text.append("(头目)");
                }
            }
            final int shown = index;
            final String body = text.toString();
            source.sendSuccess(() -> Component.literal("  第 " + (shown + 1) + " 波 "
                            + (shown == session.waveIndex ? "← 正在打：" : "：") + body)
                    .withStyle(shown == session.waveIndex ? ChatFormatting.GOLD : ChatFormatting.GRAY), false);
        }
        return defense.waves.size();
    }

    private static String towerReadout(ServerPlayer player, TrialSession session)
    {
        if (session.towerPos == null)
        {
            return "没有塔";
        }
        var entity = player.serverLevel().getBlockEntity(session.towerPos);
        if (!(entity instanceof com.trialportal.block.entity.WardPylonBlockEntity pylon))
        {
            return "读不到";
        }
        return String.format(java.util.Locale.ROOT, "%.0f/%.0f", pylon.towerHealth(), pylon.towerMax());
    }

    private static int defenseSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        TrialSession session = defenseSession(context);
        if (session == null)
        {
            return 0;
        }
        String knob = StringArgumentType.getString(context, "knob");
        double value = DoubleArgumentType.getDouble(context, "value");
        String error = DefenseEdit.setKnob(session.liveDefense, knob, value);
        if (error != null)
        {
            context.getSource().sendFailure(Component.literal("没改成：" + error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("本场已设为 " + knob + "=" + value
                        + ("ward_radius".equals(knob)
                                ? "（改的是接下来几波的出怪环带与拆塔判定半径；台面已经铺好了，要改场地宽度得下一场）" : ""))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int defenseWaveAddOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return defenseWaveAdd(context, 1, false);
    }

    private static int defenseWaveAddCount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return defenseWaveAdd(context, IntegerArgumentType.getInteger(context, "count"), false);
    }

    private static int defenseWaveAddBoss(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return defenseWaveAdd(context, IntegerArgumentType.getInteger(context, "count"),
                BoolArgumentType.getBool(context, "boss"));
    }

    private static int defenseWaveAdd(CommandContext<CommandSourceStack> context, int count, boolean boss)
            throws CommandSyntaxException
    {
        TrialSession session = defenseSession(context);
        if (session == null)
        {
            return 0;
        }
        int wave = IntegerArgumentType.getInteger(context, "wave");
        String mob = ResourceLocationArgument.getId(context, "mob").toString();
        String error = DefenseEdit.addMob(context.getSource().getServer().overworld(), session.liveDefense,
                wave, mob, count, boss);
        if (error != null)
        {
            context.getSource().sendFailure(Component.literal("没加成：" + error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("第 " + (wave + 1) + " 波已加上 " + mob + '×' + count
                        + (boss ? "（头目）" : "") + "；这一波还没开打就生效")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int defenseWaveRemove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        TrialSession session = defenseSession(context);
        if (session == null)
        {
            return 0;
        }
        int wave = IntegerArgumentType.getInteger(context, "wave");
        String error = DefenseEdit.removeWave(session.liveDefense, wave, session.waveIndex);
        if (error != null)
        {
            context.getSource().sendFailure(Component.literal("没删掉：" + error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("第 " + (wave + 1) + " 波已删掉，现在这场共 "
                        + session.liveDefense.finiteWaveCount() + " 波")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // ------------------------------------------------------------------ malice（恶意联动诊断）

    /** 联动是「装了另一个模组才有意义」的功能，所以现场证据必须在整合包里能一次读全。 */
    private static int maliceStatus(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal(MaliceLink.modPresent()
                        ? "「恶意」(l2hostility) 在场，联动开着：" + MaliceLink.active()
                        : "「恶意」(l2hostility) 不在场，本模组不会碰任何恶意等级（这一句是结论，不是报错）")
                .withStyle(MaliceLink.modPresent() ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        List<Integer> ladder = MaliceLink.ladderSample(8);
        source.sendSuccess(() -> Component.literal("阶梯前 8 级（第 1 层/第 1 波/最低档起）：" + ladder
                        + "；差值＝level_per_stage，封顶＝max_level，起点＝base_level")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("生成点请求过 " + MaliceLink.requestCount() + " 次落笔；最近 "
                        + Math.min(5, MaliceLink.recentRequests(5).size()) + " 次：" + describeRequests())
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("排队等落笔的怪：" + MaliceLink.pendingCount() + " 只")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("就近一只怪体检：/tei malice probe　·　试着写一级：/tei malice probe <等级>")
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int maliceProbe(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Mob mob = nearestMob(context);
        if (mob == null)
        {
            context.getSource().sendFailure(Component.literal("附近 32 格内没有生物，站到一只怪旁边再试"));
            return 0;
        }
        return report(context, mob, null);
    }

    private static int maliceProbeWrite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Mob mob = nearestMob(context);
        if (mob == null)
        {
            context.getSource().sendFailure(Component.literal("附近 32 格内没有生物，站到一只怪旁边再试"));
            return 0;
        }
        return report(context, mob, IntegerArgumentType.getInteger(context, "level"));
    }

    private static int report(CommandContext<CommandSourceStack> context, Mob mob, @Nullable Integer level)
    {
        String diagnosis = MaliceLink.diagnose(mob, level);
        context.getSource().sendSuccess(() -> Component
                .literal("【恶意联动】" + mob.getName().getString() + " · " + diagnosis)
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** 台账尾巴打印成一句人话：哪一级阶梯算出几级恶意，被单只怪顶掉的要标出来。 */
    private static String describeRequests()
    {
        StringBuilder text = new StringBuilder();
        for (MaliceLink.Request request : MaliceLink.recentRequests(5))
        {
            if (text.length() > 0)
            {
                text.append(" · ");
            }
            text.append("第").append(request.stageIndex() + 1).append("级→")
                    .append(request.level()).append(request.overridden() ? "（单只怪写的）" : "");
        }
        return text.length() == 0 ? "还没有（生成点一次都没调过——那就是接线没跑）" : text.toString();
    }

    /** 取指令源附近最近的一只活生物——体检的对象必须是真站在世界里的怪。 */
    @Nullable
    private static Mob nearestMob(CommandContext<CommandSourceStack> context)
    {
        var source = context.getSource();
        Vec3 center = source.getPosition();
        AABB box = new AABB(center, center).inflate(32.0D);
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob mob : source.getLevel().getEntitiesOfClass(Mob.class, box, candidate -> !candidate.isRemoved()))
        {
            double distance = mob.position().distanceToSqr(center);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }
}
