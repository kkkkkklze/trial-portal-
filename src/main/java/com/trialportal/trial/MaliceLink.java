package com.trialportal.trial;

import com.trialportal.TrialPortal;
import com.trialportal.config.TrialConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 与「恶意」(l2hostility) 的联动：把生成怪的<b>恶意等级</b>改成我们自己按阶梯算出来的数，
 * 不再由它按区块难度随手给（整合包里那位能给到上千级——我们的血量阶梯会被它整个抹平）。
 * <p>
 * <b>为什么不是在生成那一刻写完就完事</b>（对 l2hostility-2.5.19 字节码取证，2026-10-12）：
 * <ol>
 *   <li>等级住在 {@code MobTraitCap.lv}，它的 {@code init(...)} 无条件 {@code putfield lv}；</li>
 *   <li>我们造怪走 {@code EntityType#create} + {@code addFreshEntity}，不跑 {@code finalizeSpawn}，
 *       所以它挂在 {@code MobSpawnEvent.FinalizeSpawn} 上的那条初始化不会替我们触发；</li>
 *   <li>真正触发的是 {@code MobTraitCap#tick}——{@code !isInitialized()} 时去
 *       {@code ChunkDifficulty.at(维度, 坐标)} 取等级。那条 tick 在怪出生<b>之后</b>的第一个 tick 才跑。</li>
 * </ol>
 * 所以写成「先生成、把想要的等级记下来，等它那侧初始化好再落笔」，而且<b>只落一次，落完就忘</b>：
 * 之后玩家要是用 {@code /hostility mobs ... level set} 手动改这只怪，我们不抢回来。
 * <p>
 * 落笔的内容就是它自己那条命令做的事（{@code LHMobCommands.commandSetLevel}）：
 * {@code cap.setLevel(entity, lv)} 再 {@code cap.syncToClient(entity)}。{@code setLevel} 内部会拿
 * 它自己的 {@code clampLevel}（全局 max_mob_level + 按生物类型的上限）再过一道，所以我们给的数只会变小不会变大。
 * 重复落笔是安全的：它加的属性修正用的是按名字派生的固定 UUID（{@code MathHelper.getUUIDFromString}），
 * 每次先移除同 UUID 的旧修正，不会叠层。
 * <p>
 * 只按类名反射，不引它的编译期依赖——它没有 {@code api} 包，任何内部类名都可能改；
 * 拿不到就退化成「不联动」并在日志里说一次，绝不每 tick 刷屏。
 */
public final class MaliceLink
{
    /** 「恶意」那个模组的 modid。在场判定就看这个。 */
    public static final String HOSTILITY_MODID = "l2hostility";

    /** 它那边装等级的能力（capability）类；只按全名反射取。 */
    static final String CAP_CLASS_NAME = "dev.xkmc.l2hostility.content.capability.mob.MobTraitCap";

    /** 恶意等级的天花板：JSON 里的 malice_level 与配置项都用它，免得两处数字各写各的。 */
    public static final int LEVEL_CEILING = 100000;

    /** 一次生成最多排多少只待落笔的怪（场上限之外再加一道保险，防队列无限长）。 */
    static final int MAX_PENDING = 256;

    private static final Map<java.util.UUID, Pending> PENDING = new LinkedHashMap<>();

    /** 反射句柄解析失败的原因；null = 还没试过或试成了。只报一次日志用。 */
    @Nullable
    private static volatile String failureReason;
    @Nullable
    private static volatile Handles handles;

    /**
     * 生成点请求台账。存在的理由只有一条：让「三个生成点到底有没有调用、算出的是几级」这件事
     * 在**没装恶意的机器上也看得见**——不然接线对不对只能靠读源码形状，而整合包里出了问题也只能猜。
     * 它不参与任何玩法判定，环形留最近这么多条。
     */
    private static final java.util.Deque<Request> RECENT = new java.util.ArrayDeque<>();
    private static final int RECENT_CAPACITY = 128;
    private static long requestCount;

    /** 一次生成点请求：哪只怪、第几级阶梯、算出来的等级、是不是被单只怪的 malice_level 顶掉的。 */
    public record Request(java.util.UUID mob, int stageIndex, int level, boolean overridden) {}

    private MaliceLink() {}

    // ------------------------------------------------------------------ 纯决策（无头门盯这一段）

    /**
     * 阶梯序号 → 想要的恶意等级。单只怪写了 {@code malice_level} 就以它为准，两者都夹在 [0, maxLevel]。
     *
     * @param stageIndex     第几层 / 第几波 / 第几档（0 起）
     * @param perMobOverride 这只怪自己写的等级，null = 没写
     */
    public static int levelFor(int stageIndex, @Nullable Integer perMobOverride,
                               int baseLevel, int levelPerStage, int maxLevel)
    {
        int ceiling = Math.min(Math.max(0, maxLevel), LEVEL_CEILING);
        if (perMobOverride != null)
        {
            return clampToCeiling(perMobOverride.longValue(), ceiling);
        }
        // 用 long 累加：层数一路往上写的整合包（100 层、每层 +50）不该在 int 上翻成负数
        long ramp = (long) baseLevel + (long) Math.max(0, stageIndex) * (long) levelPerStage;
        return clampToCeiling(ramp, ceiling);
    }

    private static int clampToCeiling(long requested, int ceiling)
    {
        return (int) Math.max(0L, Math.min(requested, (long) ceiling));
    }

    /**
     * 各玩法的阶梯序号从哪儿来：闯关看难度档（easy/normal/hard = 0/1/2），
     * 另外三种看第几波／第几层——爬塔的 {@code waveIndex} 就是层号减一，不必再开一条分支。
     */
    public static int stageIndex(TrialMode mode, int waveIndex, @Nullable String difficultyKey)
    {
        if (mode == TrialMode.CHALLENGE)
        {
            int ordinal = ChallengeDefinition.DIFFICULTY_KEYS.indexOf(difficultyKey);
            // 认不出的档位不按 0 处理成「简单」：调用方拿不到序号就说明上游已经归一化过，这里只兜底
            return Math.max(0, ordinal);
        }
        return Math.max(0, waveIndex);
    }

    /** 是否该联动：配置开着 <b>且</b> 那个模组真在场。没装恶意时这条路一分钱成本都没有。 */
    public static boolean active()
    {
        return TrialConfig.MALICE_ENABLED.get() && modPresent();
    }

    public static boolean modPresent()
    {
        return ModList.get().isLoaded(HOSTILITY_MODID);
    }

    // ------------------------------------------------------------------ 生成点

    /**
     * 怪已进世界之后调用：把想要的等级排进队列，等它那侧初始化好由 {@link #tick()} 落笔。
     * 等级与台账算在<b>任何闸门之前</b>——这样"生成点到底跑没跑、算出的是几级"在没装恶意的机器上
     * 也看得见（{@code /tei malice status} 与无头门都读这一份台账），而不是只能靠读源码形状。
     *
     * @return 排进队列的目标等级；没联动（关着／没装／解析失败／队列满）时返回 -1
     */
    public static int onSpawn(Mob mob, int stageIndex, @Nullable Integer perMobOverride)
    {
        int level = levelFor(stageIndex, perMobOverride, TrialConfig.MALICE_BASE_LEVEL.get(),
                TrialConfig.MALICE_LEVEL_PER_STAGE.get(), TrialConfig.MALICE_MAX_LEVEL.get());
        recordRequest(new Request(mob.getUUID(), stageIndex, level, perMobOverride != null));
        if (!active())
        {
            return -1;
        }
        if (handles() == null)
        {
            return -1; // 拿不到它那侧的等级载体：已经报过一次原因，这里只闭嘴
        }
        if (PENDING.size() >= MAX_PENDING)
        {
            // 队列满 = 这一波生成得太猛，宁可漏掉这只也不要让它无限长；漏了的按它自己的等级打
            return -1;
        }
        PENDING.put(mob.getUUID(), new Pending(mob, level,
                Math.max(1, TrialConfig.MALICE_REASSERT_TICKS.get())));
        return level;
    }

    private static void recordRequest(Request request)
    {
        requestCount++;
        synchronized (RECENT)
        {
            RECENT.addLast(request);
            while (RECENT.size() > RECENT_CAPACITY)
            {
                RECENT.removeFirst();
            }
        }
    }

    /** 生成点一共请求过多少次落笔（与恶意在不在场无关，所以没装时也有数）。 */
    public static long requestCount()
    {
        return requestCount;
    }

    /** 最近 N 条请求（新的排在后面），给指令与无头门回读用。 */
    public static List<Request> recentRequests(int n)
    {
        List<Request> snapshot;
        synchronized (RECENT)
        {
            snapshot = new ArrayList<>(RECENT);
        }
        int from = Math.max(0, snapshot.size() - Math.max(0, n));
        return new ArrayList<>(snapshot.subList(from, snapshot.size()));
    }

    /** 台账清零：只给自测当基线用，正常运行不碰。 */
    public static void resetTelemetry()
    {
        requestCount = 0;
        synchronized (RECENT)
        {
            RECENT.clear();
        }
    }

    /** 每 tick 收一次队列（由 TrialEvents 的服务端 tick 转发，队空时直接返回）。 */
    public static void tick()
    {
        if (PENDING.isEmpty())
        {
            return;
        }
        if (!active())
        {
            PENDING.clear();
            return;
        }
        for (Iterator<Pending> iterator = PENDING.values().iterator(); iterator.hasNext(); )
        {
            Pending pending = iterator.next();
            if (pending.mob.isRemoved() || pending.mob.level().isClientSide)
            {
                iterator.remove();
                continue;
            }
            Object cap = capOf(pending.mob);
            if (cap == null || !isInitialized(cap))
            {
                if (--pending.ticksLeft <= 0)
                {
                    iterator.remove(); // 等不到就放手：那只怪多半被它的配置排除在外
                }
                continue;
            }
            iterator.remove(); // 只落一次：之后玩家手动改这只怪，我们不抢
            write(pending.mob, cap, pending.level);
        }
    }

    /** 会话／服务端收尾时调用，别把怪引用留在静态表里。 */
    public static void clearPending()
    {
        PENDING.clear();
    }

    public static int pendingCount()
    {
        return PENDING.size();
    }

    // ------------------------------------------------------------------ 诊断口（/tei malice）

    /** 一份能在整合包里跑的自检：反射能不能拿到、当前等级是多少、写下去能不能读回来。 */
    public static String diagnose(Mob mob, @Nullable Integer requested)
    {
        List<String> lines = new ArrayList<>();
        lines.add("恶意模组在场：" + modPresent() + "（modid " + HOSTILITY_MODID + "）");
        lines.add("配置：enabled=" + TrialConfig.MALICE_ENABLED.get()
                + " base_level=" + TrialConfig.MALICE_BASE_LEVEL.get()
                + " level_per_stage=" + TrialConfig.MALICE_LEVEL_PER_STAGE.get()
                + " max_level=" + TrialConfig.MALICE_MAX_LEVEL.get()
                + " reassert_ticks=" + TrialConfig.MALICE_REASSERT_TICKS.get());
        lines.add("待落笔的怪：" + PENDING.size() + " 只");
        if (!modPresent())
        {
            // 没装恶意时不去试反射：那只会抛 ClassNotFoundException，报出来的是一条没用的假故障
            lines.add("不在场，所以后面几步没试——反射拿的是那个模组里的类");
            return String.join("\n", lines);
        }
        if (handles() == null)
        {
            lines.add("反射拿不到等级载体：" + (failureReason == null ? "还没试过" : failureReason));
            return String.join("\n", lines);
        }
        Object cap = capOf(mob);
        if (cap == null)
        {
            lines.add("这只怪身上没有那个能力（" + mob.getName().getString() + "）——"
                    + "多半是它自己按生物类型排除了，或者还没附加上");
            return String.join("\n", lines);
        }
        lines.add("它那边初始化了吗：" + isInitialized(cap));
        lines.add("当前恶意等级：" + readLevel(cap) + "（读不到会是 -1）");
        if (requested != null)
        {
            write(mob, cap, requested);
            int after = readLevel(cap);
            lines.add("写 " + requested + " 之后读回来：" + after
                    + (after == requested.intValue() ? " · 一致" : " · 不一致（它自己那侧还有上限，见 max_mob_level）"));
        }
        return String.join("\n", lines);
    }

    /** 阶梯前几级长什么样，用来核对「跟层数/档位走」是不是自己想要的斜率。 */
    public static List<Integer> ladderSample(int stages)
    {
        List<Integer> sample = new ArrayList<>();
        for (int index = 0; index < stages; index++)
        {
            sample.add(levelFor(index, null, TrialConfig.MALICE_BASE_LEVEL.get(),
                    TrialConfig.MALICE_LEVEL_PER_STAGE.get(), TrialConfig.MALICE_MAX_LEVEL.get()));
        }
        return sample;
    }

    // ------------------------------------------------------------------ 反射层

    /** 一次性解析它那边需要的四个入口；任一处失败就记住原因并且不再重试。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static Handles handles()
    {
        Handles local = handles;
        if (local != null || failureReason != null)
        {
            return local;
        }
        try
        {
            Class<?> capClass = Class.forName(CAP_CLASS_NAME);
            Handles resolved = new Handles((Capability<Object>) capClass.getField("CAPABILITY").get(null),
                    capClass.getMethod("isInitialized"),
                    capClass.getMethod("getLevel"),
                    capClass.getMethod("setLevel", LivingEntity.class, int.class),
                    capClass.getMethod("syncToClient", LivingEntity.class));
            handles = resolved;
            TrialPortal.LOGGER.info("[trial_portal] 恶意联动已接上 {}", CAP_CLASS_NAME);
            return resolved;
        }
        catch (Throwable throwable)
        {
            failureReason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            TrialPortal.LOGGER.warn("[trial_portal] 接不上「恶意」的等级载体（{}），本次运行不做恶意联动：{}",
                    CAP_CLASS_NAME, failureReason);
            return null;
        }
    }

    @Nullable
    private static Object capOf(Mob mob)
    {
        Handles local = handles();
        if (local == null)
        {
            return null;
        }
        try
        {
            return mob.getCapability(local.capability()).orElse(null);
        }
        catch (Throwable throwable)
        {
            failOnce("读取能力失败：" + throwable);
            return null;
        }
    }

    private static boolean isInitialized(Object cap)
    {
        try
        {
            return Boolean.TRUE.equals(required().isInitialized.invoke(cap));
        }
        catch (Throwable throwable)
        {
            failOnce("读 isInitialized 失败：" + throwable);
            return false;
        }
    }

    private static int readLevel(Object cap)
    {
        try
        {
            return ((Integer) required().getLevel.invoke(cap)).intValue();
        }
        catch (Throwable throwable)
        {
            return -1;
        }
    }

    /** 与它自己那条命令同一对调用：写等级 + 同步给客户端。异常不外溢，玩法不能因为联动崩。 */
    private static void write(Mob mob, Object cap, int level)
    {
        try
        {
            required().setLevel.invoke(cap, mob, level);
            required().syncToClient.invoke(cap, mob);
        }
        catch (Throwable throwable)
        {
            failOnce("写入等级失败：" + throwable);
        }
    }

    /** 走到这一步说明句柄已经解析过（调用点都先判过 active/handles），这里只是把 Optional 拆掉。 */
    private static Handles required()
    {
        Handles local = handles;
        if (local == null)
        {
            throw new IllegalStateException("恶意联动的反射句柄还没解析");
        }
        return local;
    }

    private static void failOnce(String reason)
    {
        if (failureReason == null)
        {
            failureReason = reason;
            TrialPortal.LOGGER.warn("[trial_portal] 恶意联动停止工作：{}", reason);
            PENDING.clear();
        }
    }

    /** 反射句柄打包成一份，避免每个调用点各 try/catch 一遍取方法。 */
    private record Handles(Capability<Object> capability, Method isInitialized, Method getLevel,
                           Method setLevel, Method syncToClient) {}

    private static final class Pending
    {
        private final Mob mob;
        private final int level;
        private int ticksLeft;

        private Pending(Mob mob, int level, int ticksLeft)
        {
            this.mob = mob;
            this.level = level;
            this.ticksLeft = ticksLeft;
        }
    }
}
