package com.trialportal.block;

import com.trialportal.arena.ArenaService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 场地里的「返回野外」终端：不可破坏、无掉落，只由 ArenaService 程序化放置。
 */
public class ArenaExitBlock extends Block
{
    public ArenaExitBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer))
        {
            return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        ArenaService.returnHome(serverPlayer);
        return InteractionResult.CONSUME;
    }
}
