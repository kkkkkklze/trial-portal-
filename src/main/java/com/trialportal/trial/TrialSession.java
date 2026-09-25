package com.trialportal.trial;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一场试炼的纯状态。行为一律在 TrialManager 里，这个类只存数据，
 * 好处是「玩家此刻处于什么阶段」随时可读可打印（/tei trial status 直接用它）。
 */
public class TrialSession
{
    public enum Phase
    {
        /** 本波还有活着的怪 */
        IN_WAVE,
        /** 波与波之间的倒计时 */
        INTERMISSION,
        /** 等玩家决定：右键继续 / 潜行右键结算（只有 player-driven 模式会进这里） */
        AWAITING_DECISION
    }

    public final UUID playerId;
    public final String playerName;
    public final String definitionId;
    public final ResourceKey<Level> dimension;
    public final BlockPos origin;
    /** 场地隔间编号（0 = 大厅）。生成中心与回返判定都以 origin 为准，cell 只用于拆台与找回隔间。 */
    public int cell;

    /** 已完成波数 = 当前波下标（0 起）。爬塔时它等价于「已通过的层数」。 */
    public int waveIndex;
    public Phase phase = Phase.IN_WAVE;
    public int intermissionTicksLeft;

    public final List<TrialDefinition.RewardEntry> pending = new ArrayList<>();
    public final Set<UUID> aliveMobs = new LinkedHashSet<>();
    /** 最近一次贴到 BOSS 身上的词条 ID，用于播报与 status。 */
    public final List<String> bossAffixes = new ArrayList<>();
    /** 连续处于范围外的累计 tick；回到范围内清零。 */
    public int outsideTicks;

    public TrialSession(UUID playerId, String playerName, String definitionId, ResourceKey<Level> dimension, BlockPos origin)
    {
        this.playerId = playerId;
        this.playerName = playerName;
        this.definitionId = definitionId;
        this.dimension = dimension;
        this.origin = origin.immutable();
    }

    /** 当前是第几波（1 起）。 */
    public int waveNumber()
    {
        return this.waveIndex + 1;
    }

    public void addReward(List<TrialDefinition.RewardEntry> rewards)
    {
        for (TrialDefinition.RewardEntry reward : rewards)
        {
            if (reward == null || reward.item == null)
            {
                continue;
            }
            for (TrialDefinition.RewardEntry existing : this.pending)
            {
                if (reward.item.equals(existing.item))
                {
                    existing.count += Math.max(1, reward.count);
                    return;
                }
            }
            TrialDefinition.RewardEntry copy = new TrialDefinition.RewardEntry();
            copy.item = reward.item;
            copy.count = Math.max(1, reward.count);
            this.pending.add(copy);
        }
    }

    public String describe(TrialMode mode)
    {
        String phaseText = switch (this.phase)
        {
            case IN_WAVE -> "交战中";
            case INTERMISSION -> "下一波倒计时 " + (this.intermissionTicksLeft / 20 + 1) + " 秒";
            case AWAITING_DECISION -> "等待决定（右键继续 / 潜行右键结算）";
        };
        String progress = mode.isEndless() ? "第 " + this.waveNumber() + " 层" : "第 " + this.waveNumber() + " 波";
        return progress + " · " + phaseText + " · 存活怪 " + this.aliveMobs.size() + " · 待结算奖励 " + this.pending.size() + " 项";
    }
}
