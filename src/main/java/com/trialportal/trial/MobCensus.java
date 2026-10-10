package com.trialportal.trial;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把当前运行时<b>真注册</b>的可刷怪连属性一起落到 {@code mob-census.tsv}。
 * <p>
 * 存在的理由：给别的整合包配试炼内容时，怪的写法（血量、护甲、攻击力）只能从活注册表拿。
 * 离线进程读不到注册表，静态拆 jar 又只能看到代码里的常量（不少 mod 的血量还走配置文件与运行时计算），
 * 所以这里必须在真跑起来的 JVM 里读。数值口径与 {@link TrialManager} 生成点一致：
 * {@code type.create(level)} 之后的默认值，不含 {@code finalizeSpawn} 与难度加成。
 */
public final class MobCensus
{
    public static final String FLAG = "trial_portal.mobCensus";
    public static final String FILE_NAME = "mob-census.tsv";
    /** 放着这个空文件时，客户端一进世界就自动普查一份（不需要 OP 权限，也不需要改启动参数）。 */
    public static final String REQUEST_FILE_NAME = "mob-census.request";
    private static final String HEADER = "id\tmod\tcategory\tmonster\thealth\tarmor\tattack\tspeed"
            + "\tknockback\tfollow_range\tclass\tnote";

    private static final Logger LOGGER = LoggerFactory.getLogger("trial_portal.mobcensus");

    /** 一次运行只出一份，免得每切一次世界就重写一遍。 */
    private static volatile boolean written;

    private MobCensus() {}

    /** 服务端启动时按系统属性自动普查（无头通道，不需要玩家在场）。 */
    public static void runIfRequested(MinecraftServer server)
    {
        if (!Boolean.getBoolean(FLAG))
        {
            return;
        }
        LOGGER.info("[trial_portal] {} 已开启，开始普查注册生物", FLAG);
        LOGGER.info("[trial_portal] {}", dump(server.overworld(), null));
    }

    /**
     * 客户端侧的请求通道：整合包里跑的是官方服务端时玩家拿不到 OP，
     * 所以在「进世界」这一刻看标记文件，把结果写进客户端自己的游戏目录。
     */
    public static void respondToRequest(net.minecraft.world.level.LevelAccessor accessor)
    {
        if (written)
        {
            return;
        }
        if (!(accessor instanceof net.minecraft.world.level.Level level))
        {
            return;
        }
        if (!level.isClientSide())
        {
            return; // 服务端那份走 -Dtrial_portal.mobCensus，两条通道不重复写
        }
        Path request = Path.of(REQUEST_FILE_NAME);
        if (!Files.exists(request))
        {
            return;
        }
        written = true;
        String summary = dump(level, null);
        LOGGER.info("[trial_portal] 收到 {}，{}", REQUEST_FILE_NAME, summary);
        try
        {
            // 标记用完改名而不是删掉：下一次还想普查时改个名就能复用
            Files.move(request, Path.of(REQUEST_FILE_NAME + ".done"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException exception)
        {
            LOGGER.warn("[trial_portal] {} 改名失败：{}", REQUEST_FILE_NAME, exception);
        }
    }

    /** {@code /tei mob census [类别]}：在真世界里手动出一份，路径回给执行者。 */
    public static int command(CommandContext<CommandSourceStack> context, String categoryFilter)
    {
        String summary = dump(context.getSource().getLevel(), categoryFilter);
        Player player = context.getSource().getPlayer();
        if (player != null)
        {
            player.displayClientMessage(Component.literal("[trial_portal] " + summary), false);
        }
        else
        {
            context.getSource().sendSuccess(() -> Component.literal("[trial_portal] " + summary), false);
        }
        return 1;
    }

    /**
     * @param categoryFilter 空 = 全量；否则只留这个类别（如 {@code monster}）
     * @return 一句话摘要（写到哪里、多少条、多少条读不到属性）
     */
    public static String dump(net.minecraft.world.level.Level level, String categoryFilter)
    {
        List<Row> rows = new ArrayList<>();
        int unreadable = 0;
        for (net.minecraft.world.entity.EntityType<?> type : ForgeRegistries.ENTITY_TYPES)
        {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (id == null)
            {
                continue;
            }
            MobCategory category = type.getCategory();
            if (categoryFilter != null && !categoryFilter.isBlank()
                    && !categoryFilter.equalsIgnoreCase(category.getName()))
            {
                continue;
            }
            String note = "";
            Entity entity = null;
            try
            {
                entity = type.create(level);
            }
            catch (Throwable throwable)
            {
                note = "构造失败 " + throwable.getClass().getName();
            }
            if (!(entity instanceof Mob mob))
            {
                continue; // 不是可生成的生物（弹体、展示实体、矿车那一类）
            }
            if (entity instanceof LivingEntity living)
            {
                rows.add(new Row(id, category, baseHealth(living), read(living, Attributes.ARMOR),
                        read(living, Attributes.ATTACK_DAMAGE), read(living, Attributes.MOVEMENT_SPEED),
                        read(living, Attributes.KNOCKBACK_RESISTANCE), read(living, Attributes.FOLLOW_RANGE),
                        mob.getClass().getName(), note));
            }
            else
            {
                unreadable++;
                rows.add(new Row(id, category, -1, -1, -1, -1, -1, -1, mob.getClass().getName(),
                        note.isEmpty() ? "不是 LivingEntity，读不到属性" : note));
            }
        }
        rows.sort(Comparator.comparingDouble((Row row) -> row.health).thenComparing(row -> row.id.toString()));

        Path target = Path.of(FILE_NAME).toAbsolutePath();
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Row row : rows)
        {
            text.append(row.toLine()).append('\n');
        }
        try
        {
            Files.writeString(target, text, StandardCharsets.UTF_8);
        }
        catch (IOException exception)
        {
            return "写 " + target + " 失败：" + exception;
        }
        long monsters = rows.stream().filter(row -> "monster".equals(row.category.getName())).count();
        return "普查 " + rows.size() + " 种可生成生物（怪物类 " + monsters + "），读不到属性 " + unreadable
                + " 条，写到 " + target;
    }

    /**
     * 逐属性读，读不到就是 -1（表格里写成 n/a）：不是每种生物都挂了 {@code attack_damage}——
     * 直接 {@code getAttributeValue} 会抛 {@code Can't find attribute}，整次普查就废在这里。
     */
    private static double read(LivingEntity living, net.minecraft.world.entity.ai.attributes.Attribute attribute)
    {
        try
        {
            return living.getAttributes().hasAttribute(attribute) ? living.getAttributeValue(attribute) : -1;
        }
        catch (Throwable throwable)
        {
            return -1;
        }
    }

    /**    /**
     * 血量要从 {@code MAX_HEALTH} 属性读，不能读 {@code getMaxHealth()}：后者在构造里会被
     * 某些 mod 的 {@code finalizeSpawn} 前置逻辑改写，读基值才是"这个怪本来多少血"。
     */
    private static double baseHealth(LivingEntity living)
    {
        var instance = living.getAttribute(Attributes.MAX_HEALTH);
        return instance == null ? -1 : instance.getBaseValue();
    }

    private record Row(ResourceLocation id, MobCategory category, double health, double armor, double attack,
            double speed, double knockback, double followRange, String className, String note)
    {
        String toLine()
        {
            return id + "\t" + id.getNamespace() + "\t" + category.getName() + "\t"
                    + ("monster".equals(category.getName()) ? 1 : 0) + "\t" + round(health) + "\t"
                    + round(armor) + "\t" + round(attack) + "\t" + round(speed) + "\t" + round(knockback)
                    + "\t" + round(followRange) + "\t" + className + "\t" + note;
        }

        private static String round(double value)
        {
            return value < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", value);
        }
    }
}
