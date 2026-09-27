package com.trialportal.trial;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
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
    /**
     * 开局那一刻钉住的玩法。必须存下来：副本会话的 {@code definitionId} 是 {@code challenges/} 的 id，
     * 拿它去 {@code TrialLibrary} 反查永远得到 null——死亡与传送门那两条路以前就是这么整段跳过的
     * （人死了既不拆台也不清怪，配置 CHALLENGE_DEATH_CLEARS_PENDING 从来没生效过）。
     */
    public TrialMode mode;

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
    /** 试炼玩法的房间序列（最后一间固定是 BOSS 房）；其它玩法为 null。 */
    public java.util.List<String> roomPlan;
    /** 当前是第几间房（与 waveIndex 同步）。 */
    public int roomIndex;
    /** 当前房间的站怪点；null = 退回随机扫位。 */
    public java.util.List<net.minecraft.core.BlockPos> spawnPoints;
    /** 已经建出来的区域（含走廊），用于"别跑出场地"判定与拆场。 */
    @Nullable
    public net.minecraft.world.phys.AABB region;
    /** 沿 +Z 方向已建的进深（相对隔间原点），拆场与强制加载范围都按它算。 */
    public int reach;

    /** 资源副本 ID（只有 CHALLENGE 玩法有值）。 */
    @Nullable
    public String challengeId;
    /** 本局难度键（easy/normal/hard）。 */
    @Nullable
    public String difficulty;
    /** 副本剩余 tick；到点统一结算。 */
    public int remainingTicks;
    /** 距离下一次补充怪的冷却 tick。 */
    public int respawnCooldown;
    /** 本局已击杀数（只用于播报与 status）。 */
    public int kills;
    /** 本局的生成点/怪表轮询游标。必须按会话存：放在 manager 上就是全场共用，两个人同时开怪会互相推进落点。 */
    public int spawnCursor;
    /**
     * 最近建出来的那间 BOSS 房的中心舞台。BOSS 要站在这儿，而不是跟小怪一起排在内圈门边——
     * 房间表里三对柱子围出来的那块地就是为它留的（{@code RoomDefinition.bossAnchor}）。
     */
    @Nullable
    public BlockPos bossAnchor;
    /**
     * 本局的布局种子，开局那一刻掷一次。房间序列与室内的遮挡物都从它派生，
     * 所以"同一场重进还是那个布局"、而 #24 的外观变体也有地方挂。
     * 以前直接吃 {@code level.getRandom()}：同一场每次 tick 都在动世界随机流，布局既不可复现也没法按人区分。
     */
    public long seed;

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
