package com.trialportal.block.entity;

import com.trialportal.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 传送门方块实体：只存「这个传送门绑定到哪份试炼定义」。
 * 不存会话——会话绑在玩家身上（见 TrialManager）。
 */
public class TrialPortalBlockEntity extends BlockEntity
{
    private static final String TAG_BOUND = "bound_trial";

    @Nullable
    private String boundTrial;

    public TrialPortalBlockEntity(BlockPos pos, BlockState state)
    {
        super(ModBlockEntities.TRIAL_PORTAL.get(), pos, state);
    }

    @Nullable
    public String boundTrialId()
    {
        return this.boundTrial;
    }

    public void bind(@Nullable String definitionId)
    {
        this.boundTrial = definitionId;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        if (this.boundTrial != null)
        {
            tag.putString(TAG_BOUND, this.boundTrial);
        }
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        this.boundTrial = tag.contains(TAG_BOUND) ? tag.getString(TAG_BOUND) : null;
    }
}
