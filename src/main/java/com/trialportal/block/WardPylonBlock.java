package com.trialportal.block;

import com.trialportal.block.entity.WardPylonBlockEntity;
import com.trialportal.trial.DefenseDefinition;
import com.trialportal.trial.DefenseLibrary;
import com.trialportal.trial.TrialFeedback;
import com.trialportal.trial.TrialManager;
import com.trialportal.trial.TrialSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 守塔那座塔。两种身份同一个方块：
 * <b>大厅那座</b>是入口（没有主人，右键=开一局守塔）；<b>隔间那座</b>是被守的目标（有主人，
 * 右键=看塔血与波次）。
 * <p>
 * 按裁定它<b>没有合成配方</b>（§9 第 11 条④）——只作为结构件由场地摆放，创造栏里可以拿。
 * 这条"不许有配方"在自测里有反向断言，谁哪天补一份配方会被门拦住。
 */
public class WardPylonBlock extends BaseEntityBlock implements SneakInteractBlock
{
    public WardPylonBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL; // BaseEntityBlock 默认 INVISIBLE，我们要按自己的模型画
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new WardPylonBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit)
    {
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof
                net.minecraft.server.level.ServerPlayer serverPlayer))
        {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(pos) instanceof WardPylonBlockEntity pylon))
        {
            // 方块实体没到位（存档损坏或还没反序列化）：宁可什么都不做，也不要凭空开一局
            com.trialportal.TrialPortal.LOGGER.warn("[trial_portal] 守塔方块在 {} 没有方块实体，右键被忽略",
                    pos.toShortString());
            return InteractionResult.PASS;
        }
        if (!pylon.isEntry())
        {
            reportStatus(serverPlayer, pylon);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown())
        {
            TrialFeedback.actionbar(serverPlayer, "开始守塔请直接右键（潜行右键是提示）");
            return InteractionResult.CONSUME;
        }
        DefenseDefinition defense = DefenseLibrary.first();
        if (defense == null)
        {
            TrialFeedback.failure(serverPlayer, "defenses/ 里没有可用的守塔定义，开不了局");
            return InteractionResult.CONSUME;
        }
        com.trialportal.arena.ArenaService.beginDefense(serverPlayer, defense);
        return InteractionResult.CONSUME;
    }

    private static void reportStatus(net.minecraft.server.level.ServerPlayer player, WardPylonBlockEntity pylon)
    {
        TrialManager manager = TrialManager.get(player.server);
        TrialSession session = manager.sessionOf(player.getUUID());
        DefenseDefinition own = DefenseLibrary.get(pylon.defenseId());
        String wave = session == null ? "本场不属于你" : "第 " + (session.waveIndex + 1) + " / "
                + (own == null ? "?" : String.valueOf(own.waves.size())) + " 波";
        TrialFeedback.chat(player, "[守塔] 塔血 " + (long) pylon.towerHealth() + " / "
                + (long) pylon.towerMax() + " · " + wave + " · 守不住（塔血归零）或你死亡都算输");
    }
}
