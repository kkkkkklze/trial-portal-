package com.trialportal.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 29 项配置，分 6 组（challenge / tower / trial / general / arena / malice）。所有读取都发生在使用时
 * （不是缓存），所以改完保存后新开的会话就用新值。
 * <p>
 * 计数由 TrialDataCheck 从源码现算（写死过两次都漂了：24 与 16 都曾是错的）。
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
    public static final ForgeConfigSpec.IntValue GENERAL_ABANDON_GRACE_TICKS;

    public static final ForgeConfigSpec.IntValue ARENA_CELL_SPACING;
    public static final ForgeConfigSpec.IntValue ARENA_PLATFORM_RADIUS;
    public static final ForgeConfigSpec.IntValue ARENA_FENCE_HEIGHT;
    public static final ForgeConfigSpec.ConfigValue<String> ARENA_LIGHT_BLOCK;
    public static final ForgeConfigSpec.IntValue ARENA_LIGHT_SPACING;
    public static final ForgeConfigSpec.ConfigValue<String> ARENA_PLATFORM_BLOCK;
    public static final ForgeConfigSpec.BooleanValue ARENA_CLEAR_CELL_ON_END;

    public static final ForgeConfigSpec.BooleanValue MALICE_ENABLED;
    public static final ForgeConfigSpec.IntValue MALICE_BASE_LEVEL;
    public static final ForgeConfigSpec.IntValue MALICE_LEVEL_PER_STAGE;
    public static final ForgeConfigSpec.IntValue MALICE_MAX_LEVEL;
    public static final ForgeConfigSpec.IntValue MALICE_REASSERT_TICKS;

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
        GENERAL_ABANDON_GRACE_TICKS = BUILDER
                .comment("离开范围后给多少 tick 的宽限，期间回到范围内就不作废；0 = 一出范围立刻作废")
                .defineInRange("abandon_grace_ticks", 100, 0, 20 * 600);
        BUILDER.pop();

        BUILDER.push("arena");
        ARENA_CELL_SPACING = BUILDER
                .comment("每个玩家隔间沿 X 轴的间隔（格）。要大于两倍的 platform_radius，否则隔壁间会挨上")
                .defineInRange("cell_spacing", 160, 32, 4096);
        ARENA_PLATFORM_RADIUS = BUILDER
                .comment("平台半径（格），大厅与隔间共用；0 表示只放终端方块周围 1 格")
                .defineInRange("platform_radius", 10, 1, 48);
        ARENA_FENCE_HEIGHT = BUILDER
                .comment("台面围栏高度（格，玻璃）。怪能跳 1 格，2 格就关得住，默认 3 留余量："
                        + "矮了怪会顺着台沿走出场地、也被玩家轻易打下虚空。朝房间那一侧的门洞同样高")
                .defineInRange("fence_height", 3, 1, 8);
        ARENA_LIGHT_BLOCK = BUILDER
                .comment("隔间与走廊的默认灯方块。维度是 ambient_light=0.15 且无天光，没灯的房等于黑盒子。"
                        + "光照等级（本机 1.20.1 源码核过）：shroomlight / glowstone / sea_lantern / lantern = 15，"
                        + "soul_lantern = 10（更柔和，保留暗部）。房间可以用 light_block 字段各自覆盖")
                .define("light_block", "minecraft:shroomlight");
        ARENA_LIGHT_SPACING = BUILDER
                .comment("灯的间隔（格），沿墙环与走廊每隔这么多放一盏；0 = 全场不布灯。"
                        + "梯度靠间距而不是抬全局环境光——抬环境光会把明暗差抹平")
                .defineInRange("light_spacing", 6, 0, 32);
        ARENA_PLATFORM_BLOCK = BUILDER
                .comment("平台材质（方块 ID）")
                .define("platform_block", "minecraft:polished_blackstone_bricks");
        ARENA_CLEAR_CELL_ON_END = BUILDER
                .comment("会话结束时是否拆掉那个隔间的平台（大厅永远保留）")
                .define("clear_cell_on_end", true);
        BUILDER.pop();

        BUILDER.push("malice");
        MALICE_ENABLED = BUILDER
                .comment("装了「恶意」(l2hostility) 时，本模组是否接管生成怪的恶意等级。"
                        + "没装那个模组时这一项不起作用；关掉就完全不碰它的等级，"
                        + "怪继续按它自己那套（维度/生物群系/区块累积）生长")
                .define("enabled", true);
        MALICE_BASE_LEVEL = BUILDER
                .comment("阶梯起点的恶意等级：第 1 层 / 第 1 波 / 最低难度档拿这个值。0 = 起点不带恶意")
                .defineInRange("base_level", 0, 0, 100000);
        MALICE_LEVEL_PER_STAGE = BUILDER
                .comment("每上一层 / 每推进一波 / 每高一档难度加多少恶意等级")
                .defineInRange("level_per_stage", 1, 0, 100000);
        MALICE_MAX_LEVEL = BUILDER
                .comment("恶意等级封顶。本模组的血量已经按层递进，不封顶的话层级再高也读不出来——"
                        + "整合包里那位能给到上千级。恶意那侧还有它自己的 max_mob_level 与按生物类型的"
                        + "上限，最终生效的是三方里最小的那个")
                .defineInRange("max_level", 30, 0, 100000);
        MALICE_REASSERT_TICKS = BUILDER
                .comment("生成后最多再观察多少 tick 去落笔。它那侧的等级是怪出生后的第一个 tick 才从区块"
                        + "难度初始化好的（MobTraitCap#tick），出生瞬间写进去会被 init 覆盖，所以必须等；"
                        + "超时就没等到（那只怪的生物类型被它排除了），不影响下一只")
                .defineInRange("reassert_ticks", 40, 1, 1200);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
