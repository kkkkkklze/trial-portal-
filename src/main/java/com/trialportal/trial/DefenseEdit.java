package com.trialportal.trial;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * 创造模式下改守塔参数的**唯一写入口**（{@code /tei defense …} 走这里，别的都不许直接改字段）。
 * <p>
 * 为什么要有这一层：合法性只写在 {@link DefenseDefinition#resolve(String)} 里，
 * 命令层各自再抄一遍区间迟早和闸门抄出两个版本（真出过：数据面"写了没人读"与"读了不校验"同时存在）。
 * 所以这里的动作是**先改、再跑 {@code resolve}，不通过就整条回滚**——命令永远写不出闸门不认的内容。
 */
public final class DefenseEdit
{
    /** 一条命令里的 count 上限：防一个 typo 刷出十万只怪把服务端卡死（数据文件本身由作者自己负责）。 */
    public static final int MAX_COUNT_PER_ENTRY = 64;

    private DefenseEdit() {}

    /** 可改的旋钮名 → 落到哪个字段。加新旋钮只改这一处。 */
    public static boolean knowsKnob(String knob)
    {
        return switch (knob)
        {
            case "tower_health", "ward_radius", "intermission_ticks", "focus_share", "damage_per_hit",
                    "hit_interval_ticks" -> true;
            default -> false;
        };
    }

    /**
     * @return null 表示改成了；否则是失败原因（此时定义保持改动前的值）
     */
    public static String setKnob(DefenseDefinition defense, String knob, double value)
    {
        double oldHealth = defense.towerHealth;
        int oldRadius = defense.wardRadius;
        int oldIntermission = defense.intermissionTicks;
        double oldShare = defense.towerFocusShare;
        double oldDamage = defense.towerDamagePerHit;
        int oldInterval = defense.towerHitIntervalTicks;
        switch (knob)
        {
            case "tower_health" -> defense.towerHealth = value;
            case "ward_radius" -> defense.wardRadius = (int) Math.round(value);
            case "intermission_ticks" -> defense.intermissionTicks = (int) Math.round(value);
            case "focus_share" -> defense.towerFocusShare = value;
            case "damage_per_hit" -> defense.towerDamagePerHit = value;
            case "hit_interval_ticks" -> defense.towerHitIntervalTicks = (int) Math.round(value);
            default ->
            {
                return "不认识的旋钮 " + knob + "（能改的：tower_health / ward_radius / intermission_ticks /"
                        + " focus_share / damage_per_hit / hit_interval_ticks）";
            }
        }
        String error = defense.resolve(defense.id());
        if (error != null)
        {
            defense.towerHealth = oldHealth;
            defense.wardRadius = oldRadius;
            defense.intermissionTicks = oldIntermission;
            defense.towerFocusShare = oldShare;
            defense.towerDamagePerHit = oldDamage;
            defense.towerHitIntervalTicks = oldInterval;
            return error;
        }
        return null;
    }

    /**
     * 往某一波加一种怪。{@code waveIndex == 现有波数} 表示追加新的一波。
     * <p>
     * 怪物 id 当场核：写错一个字母时原版 {@code createMobAt} 只会 warn 一句然后跳过，
     * 那一波就永远缺一只、玩家在场外等——这是数据面"静默替换"那条族，命令口不能把它带进来。
     *
     * @return null 表示加成了；否则失败原因（定义保持原样）
     */
    public static String addMob(@Nullable ServerLevel level, DefenseDefinition defense, int waveIndex,
                                String entity, int count, boolean boss)
    {
        if (waveIndex < 0 || waveIndex > defense.waves.size())
        {
            return "波次下标要在 0~" + defense.waves.size() + "（" + defense.waves.size() + " = 追加新的一波），当前 "
                    + waveIndex;
        }
        if (count < 1 || count > MAX_COUNT_PER_ENTRY)
        {
            return "count 要在 1~" + MAX_COUNT_PER_ENTRY + "，当前 " + count;
        }
        ResourceLocation id = entity == null ? null : ResourceLocation.tryParse(entity);
        if (id == null)
        {
            return "怪物 id 写法不合法：" + entity;
        }
        // 不能用 getValue()==null 判存在：1.20.1 的 Forge 注册表对不存在的 id 会回落到默认条目
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id))
        {
            return "实体注册表里没有 " + id + "，写进波次也只会在生成点 warn 一句然后跳过";
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (level != null && !(type.create(level) instanceof Mob))
        {
            return id + " 不是能刷出来的生物（注册表里有，但生成点会跳过它）";
        }
        if (waveIndex == defense.waves.size())
        {
            defense.waves.add(new TrialDefinition.Wave());
        }
        TrialDefinition.Wave wave = defense.waves.get(waveIndex);
        TrialDefinition.MobEntry entry = new TrialDefinition.MobEntry();
        entry.entity = id.toString();
        entry.count = count;
        entry.boss = boss;
        wave.mobs.add(entry);
        String error = defense.resolve(defense.id());
        if (error != null)
        {
            wave.mobs.remove(entry);
            if (waveIndex == defense.waves.size() - 1 && defense.waves.size() > 1 && wave.mobs.isEmpty())
            {
                defense.waves.remove(waveIndex); // 刚追加的那一波没加成，别留下空波
            }
            return error;
        }
        return null;
    }

    /**
     * 删掉一整波。{@code waveIndex} 正在打、或删完就没波了都拒绝——
     * 后者会让 {@code finiteWaveCount()} 变成 0，开局第一波就取不到内容。
     */
    public static String removeWave(DefenseDefinition defense, int waveIndex, int currentWaveIndex)
    {
        if (waveIndex < 0 || waveIndex >= defense.waves.size())
        {
            return "波次下标要在 0~" + (defense.waves.size() - 1) + "，当前 " + waveIndex;
        }
        if (waveIndex == currentWaveIndex)
        {
            return "第 " + (waveIndex + 1) + " 波正在打，不能删（要改下一波就删它的下标）";
        }
        if (defense.waves.size() == 1)
        {
            return "只剩一波了，删掉就没有波次可开";
        }
        TrialDefinition.Wave removed = defense.waves.remove(waveIndex);
        String error = defense.resolve(defense.id());
        if (error != null)
        {
            defense.waves.add(waveIndex, removed); // resolve 不认就原样插回去，别只留一句"已保持删除前"
            return "删完之后这份定义不合法（已还原）：" + error;
        }
        return null;
    }
}
