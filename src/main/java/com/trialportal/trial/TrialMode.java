package com.trialportal.trial;

import com.google.gson.annotations.SerializedName;

/**
 * 各玩法的差异只声明在这里：其余代码一律按下面三个判定走，
 * 不要在别处再写 "如果是爬塔就……"。
 */
public enum TrialMode
{
    @SerializedName("challenge")
    CHALLENGE(false, false, true),

    @SerializedName("tower")
    TOWER(true, false, false),

    @SerializedName("trial")
    TRIAL(false, true, false),

    /**
     * 守塔：波次按时间自己往前走，怪有两个目标（打玩家 + 拆塔）。
     * 三个判定位都是 false——它不无尽、不等玩家点继续、也不是"不计波次持续补充"。
     */
    @SerializedName("defense")
    DEFENSE(false, false, false);

    private final boolean endless;
    private final boolean playerDriven;
    private final boolean sustained;

    TrialMode(boolean endless, boolean playerDriven, boolean sustained)
    {
        this.endless = endless;
        this.playerDriven = playerDriven;
        this.sustained = sustained;
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

    /**
     * 资源副本式玩法：**不计波次**，进场景后怪按时限持续补充到上限，杀一只攒一份奖，
     * 出去（点往返门 / 时限到 / 越界）统一结算。内容来自 `challenges/*.json`，
     * 与 `trials/*.json` 的波次定义互不相干（一个玩法只有一份真相）。
     */
    public boolean isSustained()
    {
        return this.sustained;
    }

    /**
     * 每种玩法占一段隔间编号：闯关 1 起、爬塔 1001 起、试炼 2001 起、守塔 3001 起。
     * 平台布局一样但**地方不同**——上一场没清干净的怪不会串进下一场，玩家也不会
     * 在两个玩法里看到同一块地。段与段之间留 1000 格编号（乘 cell_spacing 就是实际距离）。
     */
    public int cellBase()
    {
        return switch (this)
        {
            case CHALLENGE -> 1;
            case TOWER -> 1001;
            case TRIAL -> 2001;
            case DEFENSE -> 3001;
        };
    }

    public String jsonKey()
    {
        return this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
