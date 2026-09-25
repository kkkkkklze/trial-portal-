package com.trialportal.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 16 项配置，分 4 组。所有读取都发生在使用时（不是缓存），所以改完保存后新开的会话就用新值。
 */
public final class TrialConfig
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue CHALLENGE_DEATH_CLEARS_PENDING;

    public static final ForgeConfigSpec.BooleanValue TOWER_KEEP_RECORD_ON_DEATH;
    public static final ForgeConfigSpec.BooleanValue TOWER_DEATH_CLEARS_PENDING;
    public static final ForgeConfigSpec.IntValue TOWER_DEATH_FLOOR_PENALTY;
    public static final ForgeConfigSpec.IntValue TOWER_RESUME_OFFSET;

    public static final ForgeConfigSpec.BooleanValue TRIAL_DEATH_CLEARS_PENDING;
    public static final ForgeConfigSpec.IntValue TRIAL_BOSS_AFFIX_COUNT;
    public static final ForgeConfigSpec.DoubleValue TRIAL_AFFIX_WEIGHT_EXPONENT;

    public static final ForgeConfigSpec.IntValue GENERAL_DEFAULT_INTERMISSION_TICKS;
    public static final ForgeConfigSpec.IntValue GENERAL_DEFAULT_ABANDON_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue GENERAL_CLEAR_MOBS_ON_END;
    public static final ForgeConfigSpec.BooleanValue GENERAL_ANNOUNCE_IN_CHAT;
    public static final ForgeConfigSpec.IntValue GENERAL_TICK_INTERVAL;
    public static final ForgeConfigSpec.IntValue GENERAL_MAX_SPAWNS_PER_WAVE;
    public static final ForgeConfigSpec.IntValue GENERAL_MAX_ALIVE_MOBS;
    public static final ForgeConfigSpec.BooleanValue GENERAL_REWARD_TO_INVENTORY;

    public static final ForgeConfigSpec.IntValue ARENA_CELL_SPACING;
    public static final ForgeConfigSpec.IntValue ARENA_PLATFORM_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<String> ARENA_PLATFORM_BLOCK;
    public static final ForgeConfigSpec.BooleanValue ARENA_CLEAR_CELL_ON_END;

    static
    {
        BUILDER.push("challenge");
        CHALLENGE_DEATH_CLEARS_PENDING = BUILDER
                .comment("闯关死亡是否清空累计奖励")
                .define("death_clears_pending", true);
        BUILDER.pop();

        BUILDER.push("tower");
        TOWER_KEEP_RECORD_ON_DEATH = BUILDER
                .comment("爬塔死亡是否保留层数记录")
                .define("keep_record_on_death", true);
        TOWER_DEATH_CLEARS_PENDING = BUILDER
                .comment("爬塔死亡是否清空累计奖励（层数记录不受影响）")
                .define("death_clears_pending", true);
        TOWER_DEATH_FLOOR_PENALTY = BUILDER
                .comment("死亡后层数回退几层")
                .defineInRange("death_floor_penalty", 0, 0, 10000);
        TOWER_RESUME_OFFSET = BUILDER
                .comment("续关起点偏移；0 = 记录层 +1，-1 = 记录层本身重打")
                .defineInRange("resume_offset", 0, -1, 100);
        BUILDER.pop();

        BUILDER.push("trial");
        TRIAL_DEATH_CLEARS_PENDING = BUILDER
                .comment("试炼死亡是否清空奖励")
                .define("death_clears_pending", true);
        TRIAL_BOSS_AFFIX_COUNT = BUILDER
                .comment("BOSS 默认词条数（被 JSON 的 affix_count 覆盖）")
                .defineInRange("boss_affix_count", 3, 0, 32);
        TRIAL_AFFIX_WEIGHT_EXPONENT = BUILDER
                .comment("权重指数；调大更偏向高权重词条")
                .defineInRange("affix_weight_exponent", 1.0D, 0.1D, 10.0D);
        BUILDER.pop();

        BUILDER.push("general");
        GENERAL_DEFAULT_INTERMISSION_TICKS = BUILDER
                .comment("波次默认间隔（tick，20 tick = 1 秒）")
                .defineInRange("default_intermission_ticks", 60, 0, 20 * 600);
        GENERAL_DEFAULT_ABANDON_DISTANCE = BUILDER
                .comment("默认放弃距离（格）")
                .defineInRange("default_abandon_distance", 48, 0, 512);
        GENERAL_CLEAR_MOBS_ON_END = BUILDER
                .comment("结束时清掉未击杀的怪")
                .define("clear_mobs_on_end", true);
        GENERAL_ANNOUNCE_IN_CHAT = BUILDER
                .comment("关键事件是否进聊天栏")
                .define("announce_in_chat", true);
        GENERAL_TICK_INTERVAL = BUILDER
                .comment("判定间隔（tick），服务器卡时可调大")
                .defineInRange("tick_interval", 10, 1, 100);
        GENERAL_MAX_SPAWNS_PER_WAVE = BUILDER
                .comment("单波生成数量硬上限；超出部分直接丢弃，不会在下一波补")
                .defineInRange("max_spawns_per_wave", 64, 1, 512);
        GENERAL_MAX_ALIVE_MOBS = BUILDER
                .comment("单会话同时存在怪物上限；超出部分同样直接丢弃")
                .defineInRange("max_alive_mobs", 80, 1, 512);
        GENERAL_REWARD_TO_INVENTORY = BUILDER
                .comment("结算时奖励进背包；false 则掉在脚下。三种玩法共用这一个开关")
                .define("reward_to_inventory", true);
        BUILDER.pop();

        BUILDER.push("arena");
        ARENA_CELL_SPACING = BUILDER
                .comment("每个玩家隔间沿 X 轴的间隔（格）。要大于两倍的 platform_radius，否则隔壁间会挨上")
                .defineInRange("cell_spacing", 160, 32, 4096);
        ARENA_PLATFORM_RADIUS = BUILDER
                .comment("平台半径（格），大厅与隔间共用；0 表示只放终端方块周围 1 格")
                .defineInRange("platform_radius", 10, 1, 48);
        ARENA_PLATFORM_BLOCK = BUILDER
                .comment("平台材质（方块 ID）")
                .define("platform_block", "minecraft:polished_blackstone_bricks");
        ARENA_CLEAR_CELL_ON_END = BUILDER
                .comment("会话结束时是否拆掉那个隔间的平台（大厅永远保留）")
                .define("clear_cell_on_end", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
