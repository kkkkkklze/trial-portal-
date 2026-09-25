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
public class TrialPortalBlock extends BaseEntityBlock implements SneakInteractBlock
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
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
        {
            return InteractionResult.PASS;
        }
        TrialManager manager = TrialManager.get(level.getServer());
        TrialSession session = manager.sessionOf(player.getUUID());
        boolean sneaking = player.isShiftKeyDown();
        if (session != null)
        {
            manager.onPortalUse(serverPlayer, sneaking);
            return InteractionResult.CONSUME;
        }
        if (sneaking)
        {
            TrialFeedback.actionbar(serverPlayer, "潜行右键是结算离场；开始试炼请直接右键");
            return InteractionResult.CONSUME;
        }
        TrialDefinition definition = TrialLibrary.resolve(this.mode, boundId(level, pos));
        if (definition == null)
        {
            TrialFeedback.failure(serverPlayer, "这个传送门没有可用的试炼定义（mode=" + this.mode.jsonKey() + "）");
            return InteractionResult.CONSUME;
        }
        manager.start(serverPlayer, definition, pos);
        return InteractionResult.CONSUME;
    }

    @Nullable
    private static String boundId(Level level, BlockPos pos)
    {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof TrialPortalBlockEntity portal ? portal.boundTrialId() : null;
    }
}
