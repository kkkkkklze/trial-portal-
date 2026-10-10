package com.trialportal.block;

import com.trialportal.arena.ArenaService;
import com.trialportal.trial.TrialManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 大厅与野外之间唯一的通道，两头都能走：野外点它进大厅，场地里点它回家并结掉已攒的奖励。
 * 场地维度禁止破坏，所以场地里那几台拆不掉；野外这台可破坏、掉自己。
 */
public class ArenaExitBlock extends Block
{
    /** 这一台通道的走向——纯判定，无头门跑得动。 */
    public enum Passage
    {
        /** 人在野外：点它是进去 */
        ENTER_LOBBY,
        /** 人在场地：点它是出去（并结算） */
        RETURN_HOME
    }

    public ArenaExitBlock(Properties properties)
    {
        super(properties);
    }

    /**
     * 走向只看「在不在场地维度」：野外那台和场地里那台是同一个方块，没有第二种身份可判。
     */
    public static Passage passageFor(ResourceKey<Level> dimension)
    {
        return ArenaService.isArena(dimension) ? Passage.RETURN_HOME : Passage.ENTER_LOBBY;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer))
        {
            return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (passageFor(level.dimension()) == Passage.ENTER_LOBBY)
        {
            ArenaService.enterLobby(serverPlayer);
            return InteractionResult.CONSUME;
        }
        TrialManager.get(level.getServer()).leave(serverPlayer);
        return InteractionResult.CONSUME;
    }
}
