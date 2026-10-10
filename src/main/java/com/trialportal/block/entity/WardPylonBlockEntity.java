package com.trialportal.block.entity;

import com.trialportal.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 守塔那座塔的血量池。
 * <p>
 * 走血量池而不是让怪真啃方块：场地内禁止玩家破坏/放置是已定契约（§9 第 6 条），
 * 怪要是也在改方块，就得再管一套"重建/回滚"，两套规则迟早互相踩。
 * 归零即判负（§9 第 11 条①），由 {@code ArenaService}/{@code TrialManager} 去读 {@link #destroyed()}。
 */
public class WardPylonBlockEntity extends BlockEntity
{
    private double towerMax = 300.0D;
    private double towerHealth = 300.0D;
    /** 大厅那座塔没有主人（它是入口）；隔间里那座属于某个玩家，被守的就是它。 */
    @Nullable
    private UUID owner;
    private String defenseId = "";

    public WardPylonBlockEntity(BlockPos pos, BlockState state)
    {
        super(ModBlockEntities.WARD_PYLON.get(), pos, state);
    }

    public void configure(double maxHealth, @Nullable UUID ownerId, String defense)
    {
        this.towerMax = Math.max(1.0D, maxHealth);
        this.towerHealth = this.towerMax;
        this.owner = ownerId;
        this.defenseId = defense == null ? "" : defense;
        this.setChanged();
    }

    /**
     * 扣血。
     *
     * @return 这一次是否把它打到归零
     */
    public boolean damage(double amount)
    {
        if (this.towerHealth <= 0.0D || amount <= 0.0D)
        {
            return this.towerHealth <= 0.0D;
        }
        this.towerHealth = Math.max(0.0D, this.towerHealth - amount);
        this.setChanged();
        return this.towerHealth <= 0.0D;
    }

    public double towerHealth()
    {
        return this.towerHealth;
    }

    public double towerMax()
    {
        return this.towerMax;
    }

    public boolean destroyed()
    {
        return this.towerHealth <= 0.0D;
    }

    @Nullable
    public UUID owner()
    {
        return this.owner;
    }

    public String defenseId()
    {
        return this.defenseId;
    }

    /** 大厅那座入口塔：没有主人，右键它=开局。 */
    public boolean isEntry()
    {
        return this.owner == null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.putDouble("TowerMax", this.towerMax);
        tag.putDouble("TowerHealth", this.towerHealth);
        tag.putString("Defense", this.defenseId);
        if (this.owner != null)
        {
            tag.putUUID("Owner", this.owner);
        }
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        this.towerMax = tag.getDouble("TowerMax");
        this.towerHealth = tag.getDouble("TowerHealth");
        this.defenseId = tag.getString("Defense");
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        if (this.towerMax <= 0.0D)
        {
            this.towerMax = this.towerHealth; // 老数据或半截写入：别让塔一加载就变成 0/0
        }
    }
}
