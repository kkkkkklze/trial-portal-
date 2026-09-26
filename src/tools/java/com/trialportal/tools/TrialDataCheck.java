package com.trialportal.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.boss.BossAffix;
import com.trialportal.trial.TrialDefinition;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * gradlew checkTrialData 的校验程序：不开游戏、不需要 EULA，用运行期同一套模型类把 data/ 下的 JSON 跑一遍。
 * <p>
 * 限制：只能校验到「ID 写法合法 + 字段齐 + 结构自洽」。注册表里是否真有这个实体/物品，
 * 离线进程看不到，那一条要靠游戏内日志。
 */
public final class TrialDataCheck
{
    private static final Gson GSON = new GsonBuilder().setLenient().disableHtmlEscaping().create();
    /** 爬塔抽验的层数：覆盖每个层段边界、BOSS 层、以及远超层段表的位置。 */
    private static final int[] TOWER_SAMPLE_FLOORS = {1, 2, 9, 10, 11, 30, 31, 60, 61, 500, 9999};

    private final List<String> problems = new ArrayList<>();
    private int checkedTrials;
    private int checkedAffixes;
    private int checkedRooms;

    public static void main(String[] args) throws IOException
    {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/resources/data/trial_portal");
        TrialDataCheck check = new TrialDataCheck();
        Map<String, BossAffix> affixes = check.loadAffixes(root.resolve("boss_affixes"));
        check.checkTrials(root.resolve("trials"), affixes);
        check.checkRooms(root.resolve("arena_rooms"));

        System.out.printf(Locale.ROOT, "词条：%d 条；试炼：%d 份；房间：%d 间%n",
                check.checkedAffixes, check.checkedTrials, check.checkedRooms);
        if (check.problems.isEmpty())
        {
            System.out.println("checkTrialData 通过：没有字段缺失、没有失效引用、逐波逐层都能取到内容");
            return;
        }
        System.out.println("checkTrialData 失败，共 " + check.problems.size() + " 处：");
        check.problems.forEach(problem -> System.out.println("  - " + problem));
        System.exit(1);
    }

    private void checkRooms(Path directory) throws IOException
    {
        int normal = 0;
        int boss = 0;
        for (Path file : jsonFiles(directory))
        {
            String id = idOf(directory, file);
            RoomDefinition room = parse(file, id, RoomDefinition.class);
            if (room == null)
            {
                continue;
            }
            checkedRooms++;
            String error;
            try
            {
                error = room.resolve(id);
            }
            catch (IllegalArgumentException exception)
            {
                error = exception.getMessage();
            }
            if (error != null)
            {
                problems.add(id + "（房间）：" + error);
                continue;
            }
            if (room.bossRoom)
            {
                boss++;
            }
            else
            {
                normal++;
            }
            // 站怪点「声明数 == 实算数」这条不变量不在离线进程里查：碰 BlockPos 就会拉起
            // net.minecraft.Util 的 logger，而工具 classpath 上 slf4j 与 log4j 桥接版本不匹配（见 docs/开发文档.md §8）。
            // 它由 TrialSelfTest 在游戏进程里断言。
        }
        if (boss == 0)
        {
            problems.add("房间表里没有任何 boss_room=true 的房间，试炼玩法规划不出终点房");
        }
        if (normal == 0)
        {
            problems.add("房间表里没有任何普通房，试炼玩法规划不出前置房间");
        }
    }

    private Map<String, BossAffix> loadAffixes(Path directory) throws IOException
    {
        Map<String, BossAffix> loaded = new java.util.LinkedHashMap<>();
        for (Path file : jsonFiles(directory))
        {
            String id = idOf(directory, file);
            BossAffix affix = parse(file, id, BossAffix.class);
            if (affix == null)
            {
                continue;
            }
            checkedAffixes++;
            String error = affix.resolve(id);
            if (error != null)
            {
                problems.add(id + "（词条）：" + error);
                continue;
            }
            if (!affix.counters.hasAnyCondition())
            {
                problems.add(id + "（词条）：counters 全是默认值，永远不会命中（保底请显式写 \"always\": true）");
            }
            if (affix.meleeDamageType != null && !affix.meleeDamageType.isBlank())
        {
            checkIdShape(id, "melee_damage_type", affix.meleeDamageType); // 空串 = 不改近战伤害类型，合法
        }
            affix.attributes.keySet().forEach(key -> checkIdShape(id, "attributes", key));
            affix.effects.forEach(effect -> checkIdShape(id, "effects.effect", effect.effect));
            // 槽位名与效果/物品的存在性交给运行期日志（BossAffixLibrary.applyEquipment 会逐条 warn），
            // 离线进程一初始化 logger 就会撞上 slf4j/log4j 桥接版本冲突，所以这里刻意不碰注册表类。
            affix.equipment.forEach((slot, item) -> checkIdShape(id, "equipment." + slot, item));
            affix.equipmentEnchantments.forEach((slot, list) -> list.forEach(entry ->
            {
                int colon = entry.lastIndexOf(':');
                if (colon <= 0)
                {
                    problems.add(id + "（词条）：附魔写法应为 ID:等级，当前 " + entry);
                }
                else
                {
                    checkIdShape(id, "equipment_enchantments." + slot, entry.substring(0, colon));
                }
            }));
            affix.immuneTo.forEach(key -> checkImmunityKey(id, key));
            loaded.put(id, affix);
        }
        return loaded;
    }

    private void checkTrials(Path directory, Map<String, BossAffix> affixes) throws IOException
    {
        for (Path file : jsonFiles(directory))
        {
            String id = idOf(directory, file);
            TrialDefinition definition = parse(file, id, TrialDefinition.class);
            if (definition == null)
            {
                continue;
            }
            checkedTrials++;
            String error = definition.resolve(id);
            if (error != null)
            {
                problems.add(id + "（试炼）：" + error);
                continue;
            }
            if (definition.isEndless())
            {
                for (int floor : TOWER_SAMPLE_FLOORS)
                {
                    TrialDefinition.WaveContent content = definition.waveFor(floor - 1);
                    if (content == null || content.mobs.isEmpty())
                    {
                        problems.add(id + "（试炼）：第 " + floor + " 层取不到内容（tiers 没覆盖到）");
                        continue;
                    }
                    int max = definition.scaling.maxCountPerEntry;
                    for (TrialDefinition.MobEntry entry : content.mobs)
                    {
                        if (entry.count > max)
                        {
                            problems.add(id + "（试炼）：第 " + floor + " 层 " + entry.entity + " 的数量 "
                                    + entry.count + " 超过 max_count_per_entry=" + max + "，钳制没生效");
                        }
                        checkIdShape(id, "scaling.mob", entry.entity);
                    }
                    checkAffixPool(id, "第 " + floor + " 层 boss_affix_pool", content.affixPool, affixes);
                }
            }
            else
            {
                int waves = definition.finiteWaveCount();
                for (int index = 0; index < waves; index++)
                {
                    TrialDefinition.WaveContent content = definition.waveFor(index);
                    if (content == null)
                    {
                        problems.add(id + "（试炼）：第 " + (index + 1) + " 波取不到内容，但 finiteWaveCount 报了 " + waves);
                        break;
                    }
                    for (TrialDefinition.MobEntry entry : content.mobs)
                    {
                        checkIdShape(id, "waves.mob", entry.entity);
                    }
                    content.rewards.forEach(reward -> checkIdShape(id, "waves.reward", reward.item));
                }
                if (definition.waveFor(waves) != null)
                {
                    problems.add(id + "（试炼）：finiteWaveCount 之后还能取到波次，计数与内容不一致");
                }
            }
            if (definition.boss != null)
            {
                checkIdShape(id, "boss.entity", definition.boss.entity);
                definition.boss.rewards.forEach(reward -> checkIdShape(id, "boss.reward", reward.item));
                checkAffixPool(id, "boss.affix_pool", definition.boss.affixPool, affixes);
            }
            if (definition.scaling != null)
            {
                definition.scaling.rewards.forEach(reward -> checkIdShape(id, "scaling.reward", reward.item));
                checkAffixPool(id, "scaling.boss_affix_pool", definition.scaling.bossAffixPool, affixes);
                for (TrialDefinition.Tier tier : definition.scaling.tiers)
                {
                    tier.mobs.forEach(mob -> checkIdShape(id, "tiers.mob", mob));
                    checkAffixPool(id, "tiers[" + tier.from + "-" + tier.to + "].boss_affix_pool", tier.bossAffixPool, affixes);
                }
            }
        }
    }

    private void checkAffixPool(String trialId, String label, List<String> pool, Map<String, BossAffix> affixes)
    {
        if (pool == null)
        {
            return;
        }
        for (String referenced : pool)
        {
            if (!affixes.containsKey(referenced))
            {
                problems.add(trialId + "（试炼）：" + label + " 引用了不存在的词条 " + referenced);
            }
        }
    }

    private void checkImmunityKey(String id, String key)
    {
        switch (key.toLowerCase(Locale.ROOT))
        {
            case "all", "projectile", "fire", "explosion", "fall", "magic", "melee" ->
            {
                return;
            }
            default ->
            {
            }
        }
        if (ResourceLocation.tryParse(key) == null)
        {
            problems.add(id + "（词条）：immune_to 的 " + key + " 既不是简写也不是合法伤害类型 ID");
        }
    }

    private void checkIdShape(String owner, String field, String value)
    {
        if (value == null || value.isBlank())
        {
            problems.add(owner + "：" + field + " 为空");
            return;
        }
        if (ResourceLocation.tryParse(value) == null)
        {
            problems.add(owner + "：" + field + " 不是合法 ID（" + value + "）");
        }
    }

    private static <T> T parse(Path file, String id, Class<T> type)
    {
        try (Reader reader = Files.newBufferedReader(file))
        {
            T model = GSON.fromJson(reader, type);
            if (model == null)
            {
                System.out.println("  ! " + id + "：文件内容为空，已跳过");
            }
            return model;
        }
        catch (Exception exception)
        {
            System.out.println("  ! " + id + "：JSON 解析失败 —— " + exception.getMessage());
            return null;
        }
    }

    private static List<Path> jsonFiles(Path directory) throws IOException
    {
        if (!Files.isDirectory(directory))
        {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory))
        {
            List<Path> files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            return files;
        }
    }

    /** 试炼 / 词条 ID = &lt;命名空间&gt;:&lt;文件名&gt;（目录结构 data/&lt;命名空间&gt;/trials/xxx.json），与运行期扫描口径一致。 */
    private static String idOf(Path directory, Path file)
    {
        String name = file.getFileName().toString();
        String namespace = directory.getParent().getFileName().toString();
        return namespace + ":" + name.substring(0, name.length() - ".json".length());
    }
}
