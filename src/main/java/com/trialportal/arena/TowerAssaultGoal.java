package com.trialportal.arena;

import com.trialportal.block.entity.WardPylonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 拆塔那只怪：走过去、站定、按节奏啃塔（§9 第 11 条：怪有两个目标——打玩家，以及摧毁塔）。
 * <p>
 * 两只目标怎么不打架：{@link #canUse()} 见仇恨目标就让路，于是"没人打我就去啃塔、被打就回身"。
 * 扣的是血量池不是方块——场地内禁止破坏/放置是已定契约（§9 第 6 条），怪要是也在写方块，
 * 就得再管一套"重建/回滚"，两套规则迟早互相踩。塔归零之后 {@code canUse()} 自己就关了：
 * 那场已经判负，继续啃没有意义。
 */
public class TowerAssaultGoal extends Goal
{
    /** 贴上塔才打得到。塔只有一格宽，2.5 够几只怪站开而不重叠。 */
    public static final double REACH = 2.5D;
    /** 重新算一次路径的间隔（tick）：怪会挪动，路径不能只算一次。 */
    private static final int REPATH_TICKS = 40;
    /** 四个停靠方向（绕着塔站开），用实体 UUID 挑一个，同一只怪每次 tick 拿到的都相同。 */
    private static final double[][] STOP_OFFSETS = {{1.4, 0.0}, {-1.4, 0.0}, {0.0, 1.4}, {0.0, -1.4}};

    private final Mob mob;
    private final BlockPos towerPos;
    private final double damagePerHit;
    private final int intervalTicks;
    private final double activationRadius;
    private int hitCooldown;
    private int repathCooldown;

    /**
     * @param activationRadius 离塔心多远之内还肯去拆；出了结界就该由原版的仇恨逻辑接管
     */
    public TowerAssaultGoal(Mob mob, BlockPos towerPos, double damagePerHit, int intervalTicks, double activationRadius)
    {
        this.mob = mob;
        this.towerPos = towerPos.immutable();
        this.damagePerHit = damagePerHit;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.activationRadius = activationRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse()
    {
        // 仇恨目标优先：正在跟人打的怪不许抢它的移动（"两个目标"靠这条不打架，
        // 原版 GoalSelector 每 tick 都会用 canContinueToUse()==canUse() 把它停下来）
        return this.mob.getTarget() == null && pylon() != null;
    }

    @Override
    public void start()
    {
        this.hitCooldown = this.intervalTicks;
        this.repathCooldown = REPATH_TICKS;
        this.pathToTower();
    }

    @Override
    public void tick()
    {
        WardPylonBlockEntity tower = pylon();
        if (tower == null || this.mob.getTarget() != null)
        {
            return; // 与 canUse 同一道闸：让路给"正在跟人打"那半边，扣血不许在它背后偷偷发生
        }
        Vec3 center = Vec3.atCenterOf(this.towerPos);
        this.mob.getLookControl().setLookAt(center.x, center.y, center.z);
        if (--this.repathCooldown <= 0)
        {
            this.repathCooldown = REPATH_TICKS;
            this.pathToTower();
        }
        if (!withinReach())
        {
            return; // 还没走到，不能隔着半个结界扣塔血
        }
        if (--this.hitCooldown > 0)
        {
            return;
        }
        this.hitCooldown = this.intervalTicks;
        this.mob.swing(InteractionHand.MAIN_HAND);
        tower.damage(this.damagePerHit);
    }

    /** 扣血按 tick 累计：原版默认每两 tick 才更新一次 goal，那样间隔会悄悄变成两倍。 */
    @Override
    public boolean requiresUpdateEveryTick()
    {
        return true;
    }

    private void pathToTower()
    {
        double[] stop = STOP_OFFSETS[Math.floorMod(this.mob.getUUID().hashCode(), STOP_OFFSETS.length)];
        this.mob.getNavigation().moveTo(this.towerPos.getX() + 0.5D + stop[0], this.towerPos.getY(),
                this.towerPos.getZ() + 0.5D + stop[1], 1.0D);
    }

    private boolean withinReach()
    {
        return this.mob.position().distanceToSqr(Vec3.atCenterOf(this.towerPos)) <= REACH * REACH;
    }

    /** 塔还在、没归零、且怪还在结界边上——三条任一不成立就返回 null（归零之后不再啃：那场已经判负了）。 */
    @Nullable
    private WardPylonBlockEntity pylon()
    {
        if (this.mob.position().distanceToSqr(Vec3.atCenterOf(this.towerPos)) > activationRadius * activationRadius)
        {
            return null;
        }
        BlockEntity entity = this.mob.level().getBlockEntity(this.towerPos);
        if (!(entity instanceof WardPylonBlockEntity tower) || tower.destroyed())
        {
            return null;
        }
        return tower;
    }
}
