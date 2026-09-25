package com.trialportal.trial;

import com.google.gson.annotations.SerializedName;

/**
 * 三种玩法的差异只声明在这里：其余代码一律按 isEndless()/isPlayerDriven() 走，
 * 不要在别处再写 "如果是爬塔就……"。
 */
public enum TrialMode
{
    @SerializedName("challenge")
    CHALLENGE(false, true),

    @SerializedName("tower")
    TOWER(true, false),

    @SerializedName("trial")
    TRIAL(false, true);

    private final boolean endless;
    private final boolean playerDriven;

    TrialMode(boolean endless, boolean playerDriven)
    {
        this.endless = endless;
        this.playerDriven = playerDriven;
    }

    /** 无尽：波次由 scaling 规则按层现算，没有「最后一波」。 */
    public boolean isEndless()
    {
        return this.endless;
    }

    /** 波间是否停下等玩家决定（右键继续 / 潜行右键结算）。 */
    public boolean isPlayerDriven()
    {
        return this.playerDriven;
    }

    public String jsonKey()
    {
        return this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
