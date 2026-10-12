package com.trialportal.block;

import com.trialportal.block.entity.TrialPortalBlockEntity;
import com.trialportal.trial.TrialDefinition;
import com.trialportal.trial.TrialFeedback;
import com.trialportal.trial.TrialLibrary;
import com.trialportal.trial.TrialManager;
import com.trialportal.trial.TrialMode;
import com.trialportal.trial.TrialSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 传送门方块：三种玩法共用一个类，差别只有构造时传入的 TrialMode。
 * 它不是可穿越的传送门，而是「试炼入口 / 交互终端」——实心方块。
 */
public class TrialPortalBlock extends BaseEntityBlock
{
    private final TrialMode mode;

    public TrialPortalBlock(TrialMode mode, BlockBehaviour.Properties properties)
    {
        super(properties);
        this.mode = mode;
    }

    public TrialMode mode()
    {
        return this.mode;
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL; // BaseEntityBlock 默认 INVISIBLE，这里要按自己的方块模型渲染
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new TrialPortalBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit)
    {
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        if (hand != InteractionHand.MAIN_HAND || level.getServer() == null)
        {
            return InteractionResult.PASS;
        }
        if (!com.trialportal.trial.TrialEvents.playerInitiated(player))
        {
            // 自动机 / FakePlayer 不该替人开局，也不该把人传走
            return InteractionResult.PASS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
        {
            return InteractionResult.PASS;
        }
        com.trialportal.TrialPortal.LOGGER.debug("[trial_portal] 右键 {} @ {} 维度 {} 潜行={}",
                pos.toShortString(), describe(state), level.dimension().location(), player.isShiftKeyDown());
        TrialManager manager = TrialManager.get(level.getServer());
        TrialSession session = manager.sessionOf(player.getUUID());
        boolean sneaking = player.isShiftKeyDown();

        if (!com.trialportal.arena.ArenaService.isArena(level.dimension()))
        {
            // 野外这扇门直连它自己的玩法：点闯关门就是开一局副本。潜行右键才进大厅（想换玩法/参观时用）。
            if (sneaking)
            {
                com.trialportal.arena.ArenaService.enterLobby(serverPlayer);
                return InteractionResult.CONSUME;
            }
            com.trialportal.arena.ArenaService.enterPlay(serverPlayer, this.mode,
                    TrialLibrary.resolve(this.mode, boundId(level, pos)));
            return InteractionResult.CONSUME;
        }

        if (session != null)
        {
            // 玩法看会话自己钉的那个：守塔/副本的内容不在 trials/ 里，查不到定义不等于"可以随便串门"
            if (session.modeOf(TrialLibrary.get(session.definitionId)) != this.mode)
            {
                TrialFeedback.failure(serverPlayer, "你当前那一场是「" + session.displayName()
                        + "」，先用对应的门结算");
                return InteractionResult.CONSUME;
            }
            manager.onPortalUse(serverPlayer, sneaking);
            return InteractionResult.CONSUME;
        }
        if (sneaking)
        {
            TrialFeedback.actionbar(serverPlayer, "潜行右键是结算离场；开始试炼请直接右键");
            return InteractionResult.CONSUME;
        }
        com.trialportal.arena.ArenaService.enterPlay(serverPlayer, this.mode,
                TrialLibrary.resolve(this.mode, boundId(level, pos)));
        return InteractionResult.CONSUME;
    }

    private static String describe(BlockState state)
    {
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
    }

    @Nullable
    private static String boundId(Level level, BlockPos pos)
    {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof TrialPortalBlockEntity portal ? portal.boundTrialId() : null;
    }
}
