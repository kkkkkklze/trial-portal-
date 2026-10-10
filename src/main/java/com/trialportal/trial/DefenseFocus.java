package com.trialportal.trial;

/**
 * 一只守塔怪这一局追谁：玩家，还是塔。
 * <p>
 * 故意做成不 import Minecraft 的纯判断——"这只怪有没有真去啃塔"要靠实体 tick，无头门看不见；
 * 但"一波里两支目标都得有人"这条性质可以在这里断言，而且必须由这里说了算，
 * 免得生成点各写各的 {@code random < share} 漂移成两套口径。
 */
public enum DefenseFocus
{
    /** 锁玩家：走原来的 {@code setTarget} 那条路 */
    PLAYER,
    /** 奔着塔去：挂 {@code TowerAssaultGoal}，开局不锁玩家（挨了打才回身，这是原版 {@code HurtByTargetGoal} 的事） */
    TOWER;

    /**
     * 按 [0,1) 的随机数与定义级比例挑一支。
     * 越界的 roll 不可能是随机流给的（多半是调用点算错了），稳妥地归给玩家那支，不猜。
     */
    public static DefenseFocus forRoll(double roll, double towerFocusShare)
    {
        if (roll < 0.0D || roll >= 1.0D)
        {
            return PLAYER;
        }
        return roll < towerFocusShare ? TOWER : PLAYER;
    }
}
