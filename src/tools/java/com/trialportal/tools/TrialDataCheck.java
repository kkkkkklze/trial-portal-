package com.trialportal.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.trial.DefenseDefinition;
import com.trialportal.boss.BossAffix;
import com.trialportal.trial.ChallengeDefinition;
import com.trialportal.trial.TrialDefinition;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private int checkedChallenges;
    private int checkedRecipes;
    private int checkedDefenses;

    public static void main(String[] args) throws IOException
    {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/resources/data/trial_portal");
        TrialDataCheck check = new TrialDataCheck();
        Map<String, BossAffix> affixes = check.loadAffixes(root.resolve("boss_affixes"));
        check.checkTrials(root.resolve("trials"), affixes);
        check.checkRooms(root.resolve("arena_rooms"));
        check.checkChallenges(root.resolve("challenges"));
        check.checkDefenses(root.resolve("defenses"));
        check.checkRecipes(root.resolve("recipes"));
        check.checkLootTables(root.resolve("loot_tables/blocks"));

        System.out.printf(Locale.ROOT, "词条：%d 条；试炼：%d 份；房间：%d 间；副本：%d 份；配方：%d 条；守塔：%d 份%n",
                check.checkedAffixes, check.checkedTrials, check.checkedRooms, check.checkedChallenges,
                check.checkedRecipes, check.checkedDefenses);
        if (check.problems.isEmpty())
        {
            System.out.println("checkTrialData 通过：没有字段缺失、没有失效引用、逐波逐层都能取到内容");
            return;
        }
        System.out.println("checkTrialData 失败，共 " + check.problems.size() + " 处：");
        check.problems.forEach(problem -> System.out.println("  - " + problem));
        System.exit(1);
    }

    /**
     * 配方按<b>原版的形状</b>判。离线进程里没有物品注册表（而且一碰 {@code ItemStack} 就要拉日志，
     * 同 {@code BlockPos} 那条限制），所以这里只能查到"原版加载器会不会接受这份 JSON"这一层，
     * 真能不能合成、产物是不是自家方块，由 {@code TrialSelfTest} 在有注册表的 JVM 里对账。
     * <p>
     * 文件名与产物必须一致：{@code tower_portal.json} 合成出 {@code challenge_portal} 这种复制粘贴漏改，
     * 加载一切正常、运行期一声不响，只是有个门永远合不出来。
     */
    private void checkRecipes(Path directory) throws IOException
    {
        for (Path file : jsonFiles(directory))
        {
            String name = file.getFileName().toString().replace(".json", "");
            String where = "配方 " + name;
            checkedRecipes++;
            JsonObject recipe;
            try (Reader reader = Files.newBufferedReader(file))
            {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject())
                {
                    problems.add(where + "：根不是对象");
                    continue;
                }
                recipe = json.getAsJsonObject();
            }
            catch (Exception exception)
            {
                problems.add(where + "：JSON 解析失败 —— " + exception.getMessage());
                continue;
            }
            for (String key : recipe.keySet())
            {
                if (!RECIPE_KEYS.contains(key))
                {
                    problems.add(where + "：不认识的值 " + key + "（原版会静默忽略它）");
                }
            }
            String type = text(recipe, "type");
            boolean shaped = "minecraft:crafting_shaped".equals(type);
            if (!shaped && !"minecraft:crafting_shapeless".equals(type))
            {
                problems.add(where + "：type 只支持 crafting_shaped / crafting_shapeless，当前 " + type);
                continue;
            }
            if (shaped && checkShaped(recipe, where))
            {
                continue; // 形状已经错了，产物那步不必再报
            }
            checkRecipeResult(recipe, name, where);
        }
        if (checkedRecipes == 0)
        {
            // 与房间表/副本那两条同一个道理：目录被清空时不能一声不响地绿过去
            problems.add("recipes/ 一份配方都没有：往返门只能靠创造栏或 /give 拿到，生存里进不去大厅");
        }
    }

    /** @return true 表示已记问题 */
    private boolean checkShaped(JsonObject recipe, String where)
    {
        JsonElement patternJson = recipe.get("pattern");
        if (patternJson == null || !patternJson.isJsonArray() || patternJson.getAsJsonArray().size() > 3)
        {
            problems.add(where + "：pattern 缺失或超过 3 行（合成格只有 3x3）");
            return true;
        }
        JsonElement keyJson = recipe.get("key");
        if (keyJson == null || !keyJson.isJsonObject())
        {
            problems.add(where + "：shaped 配方必须有 key");
            return true;
        }
        JsonArray pattern = patternJson.getAsJsonArray();
        JsonObject keys = keyJson.getAsJsonObject();
        int width = -1;
        Set<String> used = new HashSet<>();
        for (JsonElement row : pattern)
        {
            String line = row.getAsString();
            if (width < 0)
            {
                width = line.length();
            }
            else if (line.length() != width)
            {
                problems.add(where + "：pattern 每行必须等宽（" + width + " 与 " + line.length() + "）");
                return true;
            }
            for (char symbol : line.toCharArray())
            {
                if (symbol == ' ')
                {
                    continue;
                }
                used.add(String.valueOf(symbol));
                if (!keys.has(String.valueOf(symbol)))
                {
                    problems.add(where + "：pattern 用了 '" + symbol + "' 但 key 里没有这一项");
                    return true;
                }
            }
        }
        for (String symbol : keys.keySet())
        {
            if (symbol.length() != 1)
            {
                problems.add(where + "：key 的键名必须是单个字符（当前 " + symbol + "）");
                return true;
            }
            if (!used.contains(symbol))
            {
                problems.add(where + "：key 里的 '" + symbol + "' 没被 pattern 用到（多半是打错了）");
                return true;
            }
        }
        return false;
    }

    private void checkRecipeResult(JsonObject recipe, String name, String where)
    {
        JsonElement resultJson = recipe.get("result");
        if (resultJson == null || !resultJson.isJsonObject() || !resultJson.getAsJsonObject().has("item"))
        {
            problems.add(where + "：result 必须是带 item 的对象");
            return;
        }
        JsonObject result = resultJson.getAsJsonObject();
        String item = text(result, "item");
        ResourceLocation key = ResourceLocation.tryParse(item);
        if (key == null)
        {
            problems.add(where + "：result.item 不是合法 ID（" + item + "）");
            return;
        }
        if (!"trial_portal".equals(key.getNamespace()))
        {
            problems.add(where + "：产物不是本模组的方块（" + item + "）");
        }
        if (!key.getPath().equals(name))
        {
            problems.add(where + "：文件名是 " + name + " 但产物是 " + key.getPath()
                    + "（复制粘贴漏改，加载不报错、那个门却永远合不出来）");
        }
        if (result.has("count") && result.get("count").getAsInt() < 1)
        {
            problems.add(where + "：result.count 至少 1");
        }
        JsonElement ingredients = recipe.get("ingredients");
        if (ingredients != null)
        {
            if (!ingredients.isJsonArray() || ingredients.getAsJsonArray().isEmpty())
            {
                problems.add(where + "：ingredients 必须是非空数组");
                return;
            }
            for (JsonElement entry : ingredients.getAsJsonArray())
            {
                checkIngredientEntry(entry, where);
            }
        }
        JsonElement keys = recipe.get("key");
        if (keys != null && keys.isJsonObject())
        {
            for (String symbol : keys.getAsJsonObject().keySet())
            {
                JsonElement option = keys.getAsJsonObject().get(symbol);
                if (option.isJsonArray())
                {
                    for (JsonElement each : option.getAsJsonArray())
                    {
                        checkIngredientEntry(each, where);
                    }
                }
                else
                {
                    checkIngredientEntry(option, where);
                }
            }
        }
    }

    /** 取一个字符串值；缺失或不是字符串就返回空串，交给调用方按"不合法 ID"报出来。 */
    private static String text(JsonObject object, String field)
    {
        JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    /** 一份输入：必须是 {@code {"item": …}} 或 {@code {"tag": …}}，ID 写法要合法。 */
    private void checkIngredientEntry(JsonElement entry, String where)
    {
        if (entry == null || !entry.isJsonObject())
        {
            problems.add(where + "：输入项必须是 {item} 或 {tag} 对象");
            return;
        }
        JsonObject object = entry.getAsJsonObject();
        String field = object.has("tag") ? "tag" : (object.has("item") ? "item" : null);
        if (field == null)
        {
            problems.add(where + "：输入项既没有 item 也没有 tag");
            return;
        }
        String value = text(object, field);
        if (ResourceLocation.tryParse(value) == null)
        {
            problems.add(where + "：输入项的 " + field + " 不是合法 ID（" + value + "）");
        }
    }

    /** 原版 1.20.1 合成配方认的值。写错名字原版加载器会静默忽略，所以这里直接红。 */
    private static final Set<String> RECIPE_KEYS = Set.of(
            "type", "group", "category", "pattern", "key", "ingredients", "result");

    /**
     * 掉落表按形状判：表是否真注册到方块上由运行期判据管（这里没有注册表）。
     * 这条值得单独立门——配方上线后门方块有了真实成本，表写错就是玩家一镐亏掉一颗下界星，
     * 而原版加载器一声不响。
     */
    private void checkLootTables(Path directory) throws IOException
    {
        int checked = 0;
        for (Path file : jsonFiles(directory))
        {
            String name = file.getFileName().toString().replace(".json", "");
            String where = "掉落表 " + name;
            checked++;
            JsonElement json;
            try (Reader reader = Files.newBufferedReader(file))
            {
                json = JsonParser.parseReader(reader);
            }
            catch (Exception exception)
            {
                problems.add(where + "：JSON 解析失败 —— " + exception.getMessage());
                continue;
            }
            if (!json.isJsonObject())
            {
                problems.add(where + "：根不是对象");
                continue;
            }
            JsonObject table = json.getAsJsonObject();
            if (!"minecraft:block".equals(text(table, "type")))
            {
                problems.add(where + "：type 应为 minecraft:block（当前 " + text(table, "type") + "）");
            }
            JsonElement pools = table.get("pools");
            if (pools == null || !pools.isJsonArray() || pools.getAsJsonArray().isEmpty())
            {
                problems.add(where + "：pools 是空的，敲掉这个方块什么都不掉");
                continue;
            }
            for (JsonElement pool : pools.getAsJsonArray())
            {
                JsonElement entries = pool.isJsonObject() ? pool.getAsJsonObject().get("entries") : null;
                if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty())
                {
                    problems.add(where + "：有一个 pool 没有 entries");
                    continue;
                }
                for (JsonElement entry : entries.getAsJsonArray())
                {
                    checkLootEntry(where, name, entry);
                }
            }
        }
        if (checked == 0)
        {
            problems.add("loot_tables/blocks/ 一份掉落表都没有：门方块敲掉不返还，而它们现在是有成本的");
        }
    }

    private void checkLootEntry(String where, String fileName, JsonElement entry)
    {
        if (!entry.isJsonObject())
        {
            problems.add(where + "：有条目不是对象");
            return;
        }
        JsonObject object = entry.getAsJsonObject();
        String type = text(object, "type");
        if (!type.isEmpty() && !"minecraft:item".equals(type))
        {
            problems.add(where + "：条目类型 " + type + " 不是 item（这张表是给方块掉落的）");
            return;
        }
        String item = text(object, "name");
        ResourceLocation key = ResourceLocation.tryParse(item);
        if (key == null)
        {
            problems.add(where + "：条目的 name 不是合法 ID（" + item + "）");
            return;
        }
        if (!key.getPath().equals(fileName))
        {
            problems.add(where + "：文件叫 " + fileName + " 却掉 " + key.getPath()
                    + "（表名与方块名不一致时原版根本不会用它，等于没掉）");
        }
    }

    /** 资源副本：三档难度必须齐全，且每档都要有怪、有上限、有补充节奏（杀奖可以为空，表示这档不给奖）。 */
    private void checkChallenges(Path directory) throws IOException
    {
        for (Path file : jsonFiles(directory))
        {
            String id = idOf(directory, file);
            ChallengeDefinition challenge = parse(file, id, ChallengeDefinition.class);
            if (challenge == null)
            {
                continue;
            }
            checkedChallenges++;
            String error;
            try
            {
                error = challenge.resolve(id);
            }
            catch (IllegalArgumentException exception)
            {
                error = exception.getMessage();
            }
            if (error != null)
            {
                problems.add(id + "（副本）：" + error);
                continue;
            }
            ChallengeDefinition.Difficulty previous = null;
            for (String key : ChallengeDefinition.DIFFICULTY_KEYS)
            {
                ChallengeDefinition.Difficulty difficulty = challenge.difficulty(key);
                if (difficulty == null)
                {
                    continue; // resolve() 已经报过
                }
                if (previous != null && difficulty.mobCap < previous.mobCap)
                {
                    problems.add(id + "（副本）：" + key + " 的 mob_cap " + difficulty.mobCap
                            + " 低于上一档，难度顺序反了");
                }
                previous = difficulty;
            }
        }
        if (checkedChallenges == 0)
        {
            problems.add("challenges/ 里一份副本都没有，闯关玩法就没内容可选");
        }
    }

    /** 守塔表：波次复用 trials 的 Wave 模型，这里只额外核"有没有内容"与每波展开后至少一只。 */
    private void checkDefenses(Path directory) throws IOException
    {
        for (Path file : jsonFiles(directory))
        {
            String id = idOf(directory, file);
            DefenseDefinition defense = parse(file, id, DefenseDefinition.class);
            if (defense == null)
            {
                continue;
            }
            checkedDefenses++;
            String error;
            try
            {
                error = defense.resolve(id);
            }
            catch (IllegalArgumentException exception)
            {
                error = exception.getMessage();
            }
            if (error != null)
            {
                problems.add(id + "（守塔）：" + error);
                continue;
            }
            for (int index = 0; index < defense.waves.size(); index++)
            {
                int total = 0;
                for (TrialDefinition.MobEntry entry : defense.waves.get(index).mobs)
                {
                    total += Math.max(1, entry.count);
                }
                if (total == 0)
                {
                    problems.add(id + "（守塔）：第 " + (index + 1) + " 波展开后一只都不剩");
                }
            }
        }
        if (checkedDefenses == 0)
        {
            problems.add("defenses/ 里一份守塔定义都没有，守塔玩法就没内容可开");
        }
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
                if (definition.scaling.maxCountPerEntry < 1)
                {
                    // 每层算出 0 只怪 → 波次"立刻清空"并放行下一层，整场白送（ScalingRule.resolve 只钳制不拒绝）
                    problems.add(id + "（试炼）：scaling.max_count_per_entry=" + definition.scaling.maxCountPerEntry
                            + "，每层都会算出 0 只怪");
                }
                if (definition.scaling.bossEveryFloors > 0
                        && definition.scaling.tiers.stream().noneMatch(tier -> tier.boss))
                {
                    problems.add(id + "（试炼）：设了 boss_every_floors=" + definition.scaling.bossEveryFloors
                            + "，但没有任何层段标 boss=true，BOSS 波永远不会出现");
                }
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

    private <T> T parse(Path file, String id, Class<T> type)
    {
        try (Reader reader = Files.newBufferedReader(file))
        {
            JsonElement json = JsonParser.parseReader(reader);
            checkKeys(id, json, type);
            T model = GSON.fromJson(json, type);
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

    /**
     * 字段白名单。Gson 对"模型类里没有这个字段"是**静默忽略**的：把 `spawn_radius` 写成 `spawn_raduis`
     * 一样能加载成功，运行期悄悄用默认值——离线门是唯一能把它当错误报出来的地方。
     * 只递归自家的模型类；Map 字段（词条参数那种开放键）与 JDK 类型不进去。
     */
    private void checkKeys(String where, JsonElement json, Class<?> type)
    {
        if (json.isJsonObject())
        {
            checkObjectKeys(where, json.getAsJsonObject(), type);
        }
    }

    private void checkObjectKeys(String where, JsonObject object, Class<?> type)
    {
        if (!isModel(type))
        {
            return;
        }
        for (Map.Entry<String, JsonElement> member : object.entrySet())
        {
            Field field = fieldNamed(type, member.getKey());
            if (field == null)
            {
                if (isCommentKey(member.getKey()))
                {
                    continue; // JSON 没有注释，内容文件里的 "comment"/"_note" 是写给人看的，加载器与门都该无视它
                }
                problems.add(where + " 里有个模型类不认识的字段 \"" + member.getKey()
                        + "\"——Gson 会静默丢掉它，多半是拼错了");
                continue;
            }
            JsonElement value = member.getValue();
            if (value.isJsonObject())
            {
                Class<?> valueType = mapValueType(field);
                if (valueType != null)
                {
                    // Map 字段的键是数据不是字段名（difficulties 的 easy/normal/hard），
                    // 之前整棵子树因此绕过了白名单——把键名拼错、把 spawn_batch 写成 spawn_bat 都没人管。
                    for (Map.Entry<String, JsonElement> keyed : value.getAsJsonObject().entrySet())
                    {
                        if (keyed.getValue().isJsonObject())
                        {
                            checkObjectKeys(where + "." + member.getKey() + "[" + keyed.getKey() + "]",
                                    keyed.getValue().getAsJsonObject(), valueType);
                        }
                    }
                }
                else
                {
                    checkObjectKeys(where + "." + member.getKey(), value.getAsJsonObject(), field.getType());
                }
            }
            else if (value.isJsonArray())
            {
                Class<?> element = listItemType(field);
                int index = 0;
                for (JsonElement item : value.getAsJsonArray())
                {
                    if (item.isJsonObject() && element != null)
                    {
                        checkObjectKeys(where + "." + member.getKey() + "[" + index + "]",
                                item.getAsJsonObject(), element);
                    }
                    index++;
                }
            }
        }
    }

    private static boolean isModel(Class<?> type)
    {
        return type != null && type.getName().startsWith("com.trialportal.");
    }

    private static boolean isCommentKey(String key)
    {
        return key.equals("comment") || key.startsWith("_");
    }

    /** 认字段名，也认 @SerializedName 改过的名字（含父类字段）。 */
    private static Field fieldNamed(Class<?> type, String key)
    {
        for (Class<?> current = type; current != null && isModel(current); current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
            {
                if (field.isSynthetic())
                {
                    continue; // static 的 GSON 缓存一类不算内容字段
                }
                SerializedName named = field.getAnnotation(SerializedName.class);
                if (key.equals(named != null ? named.value() : field.getName()))
                {
                    return field;
                }
            }
        }
        return null;
    }

    /** List<X> 里的 X；不是 List 或元素不是自家模型就返回 null。 */
    private static Class<?> listItemType(Field field)
    {
        if (!List.class.isAssignableFrom(field.getType())
                || !(field.getGenericType() instanceof ParameterizedType parameterized))
        {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments().length == 0
                ? null : parameterized.getActualTypeArguments()[0];
        return argument instanceof Class<?> element && isModel(element) ? element : null;
    }

    /** Map&lt;K, X&gt; 里的 X。值类型不是自家模型就返回 null（词条 attributes 那种开放键字典不进去）。 */
    private static Class<?> mapValueType(Field field)
    {
        if (!Map.class.isAssignableFrom(field.getType())
                || !(field.getGenericType() instanceof ParameterizedType parameterized))
        {
            return null;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 2 && arguments[1] instanceof Class<?> valueType && isModel(valueType)
                ? valueType : null;
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
