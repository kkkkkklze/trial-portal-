"""把 mob-census.tsv 变成一份 world datapack：闯关副本 + 爬塔层段，都按怪物血量递进。

口径全部来自普查文件里的真实数值，不写死任何怪名或血量。分档边界由数据自己算，
所以换一份普查结果重跑，档位会跟着移动（不会出现"某档空了但还挂着旧怪"）。

用法：
    py tools/census_to_pack.py --census run/mob-census.tsv --out build/nw-ladder
生成完会拿工程自己的离线校验器（同一套模型类）复扫一遍，扫不过就直接失败退出。
"""
import argparse
import json
import pathlib
import random
import re
import statistics
import shutil
import subprocess
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent
# 默认剔除表：这些是 mod 把"伤害特效"注册成实体的常见写法（飞剑、火球、黑洞、光束、法环、指示器、假人…）。
# 它们照样有 attack/血量属性（照抄宿主），所以只能按名字剔；名单是两台整合包实测出来的，宁可保守。
# 注意没进名单的：spirit / soul / bomb 一类里 aether:sun_spirit 是真 BOSS，不能误杀。
DEFAULT_EXCLUDES = (
    '.*(_sword|_fireball|_arrow|_breath|_projectile|_proj|_ring|_beam|_vfx|_aura|_spike|_wave|_entity'
    '|_egg|_wall|_laser|_cannon|_slash|_spell|kelp|vine|decoy|dummy|cinematic|black_hole|meteorite'
    '|directionlock|prefight|downed|cube_of|guided_energy|helper_|_spawn_|_death_)')

# 第二道形状判据。上面那份是"带下划线"的写法（`_beam`、`helper_`），而有的 mod 把特效实体名字
# 连成一串——星罗棋布里 `headhunter_mod:helperchargecontinuepistol`（1000 血、速度 0）与
# `headhunter_mod:shadow_grab_hand`（999 血）就是靠照抄宿主属性混过 attack>0 那道关的，下划线版一条都不命中。
# 只比**冒号后面那段**与类名：整条 id 里含命名空间，`block_factorys_bosses` 自带 "lock"，
# 拿 `warlock`/`blocknight` 这类真怪误杀过一次就是这么来的。
EFFECT_NAME_TOKENS = (
    'helper', 'grabhand', 'beam', 'vfx', 'laser', 'explosion', 'dummy', 'directionlock', 'prefight',
    'tentacle', 'simulacrum', 'aura', 'swirl', 'pulse', 'trigger', 'marker', 'orbit', 'halo',
    'projectile', 'blackhole', 'cubeof', 'guidedenergy', 'downed', 'meteor')


def _plain(text):
    return re.sub(r'[^a-z0-9]', '', (text or '').lower())


def effect_shaped(entity_id, class_name=''):
    """命中就返回那个关键字，没命中返回 None——留痕时要说清是被哪一条挡的。"""
    name = _plain(entity_id.split(':')[-1])
    cls = _plain(class_name.split('.')[-1])
    return next((token for token in EFFECT_NAME_TOKENS
                 if token in name or (cls and token in cls)), None)


BAND_SPANS = [(1, 5), (6, 12), (13, 20), (21, 32), (33, 48), (49, 9999)]
BAND_NAMES = ['哨站', '矿脉', '遗迹', '深窟', '王城', '终局']
# 只用来做图标与奖励：必须是原版物品，任何整合包都有
ICONS = ['minecraft:iron_sword', 'minecraft:bow', 'minecraft:golden_apple', 'minecraft:diamond',
         'minecraft:netherite_scrap', 'minecraft:enchanted_golden_apple']
REWARD_LADDER = [
    [{'item': 'minecraft:cooked_beef', 'count': 2}],
    [{'item': 'minecraft:iron_ingot', 'count': 2}, {'item': 'minecraft:coal', 'count': 2}],
    [{'item': 'minecraft:gold_ingot', 'count': 2}, {'item': 'minecraft:lapis_lazuli', 'count': 2}],
    [{'item': 'minecraft:diamond', 'count': 1}, {'item': 'minecraft:golden_apple', 'count': 1}],
    [{'item': 'minecraft:diamond', 'count': 3}, {'item': 'minecraft:experience_bottle', 'count': 4}],
    [{'item': 'minecraft:netherite_scrap', 'count': 1}, {'item': 'minecraft:enchanted_golden_apple',
                                                          'count': 1}],
]
BAND_STRENGTH = [
    {},
    {'armor_bonus': 1.0},
    {'armor_bonus': 2.0, 'knockback_resistance': 0.2},
    {'armor_bonus': 3.0, 'knockback_resistance': 0.3, 'damage_multiplier': 1.15},
    {'armor_bonus': 4.0, 'knockback_resistance': 0.4, 'damage_multiplier': 1.3},
    {'armor_bonus': 6.0, 'knockback_resistance': 0.6, 'damage_multiplier': 1.45},
]


def read_census(path, max_health, exclude_patterns):
    """返回 [(id, mod, category, health, armor, attack)]，只留怪物类、打得动、属性读得出来的条目。

    五类条目会被剔掉，而且都要留痕（不是静默丢）：
    ① 血量 > max_health——星罗棋布那份里 `powerful_dummy:test_dummy` 是 9.2e18 血的假人，
       NW 那份里两个 999,999,999 血的无敌测试 BOSS，留在池子里那场永远打不完；
    ② attack_damage <= 0——整合包把伤害特效也注册成"怪物类"实体（黑洞、光束、法环、指示器），
       它们既不会走过来也不会掉血，玩家对着空气砍。副作用：原版恶魂那类"伤害写在火球里"的
       远程怪也会被这条剔掉，数量很少，宁可少几只是真的打不动的；
    ③ 命中 exclude_patterns 的 id——同样是效果实体，但 attack 属性照抄了宿主数值，只能按名字剔；
    ④ 命中 EFFECT_NAME_TOKENS 的（按名字后段与类名的形状，不看命名空间）——②③ 两道的补集，
       专治 `helperchargecontinuepistol` 这种连写名字；
    ⑤ 血量顶到上限（>= 999）又不会移动（速度 <= 0）——站着不动的"满血宿主"，是特效实体最常见的形状。
       不单独用"速度 0"当判据：`block_factorys_bosses:kraken`（500 血、速度 0）是真 BOSS，误杀不起。
    """
    rows = []
    dropped = []
    header = None
    for line in pathlib.Path(path).read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        cells = line.split('\t')
        if header is None:
            header = cells
            continue
        if len(cells) < 7:
            continue
        entity_id, mod, category, _monster, health, armor, attack = cells[:7]
        speed = cells[7] if len(cells) > 7 else ''
        class_name = cells[10] if len(cells) > 10 else ''
        try:
            hp = float(health)
        except ValueError:
            dropped.append((entity_id, '血量读不出来（' + health + '）'))
            continue
        try:
            at = float(attack)
        except ValueError:
            at = 0.0  # 没挂 attack_damage 属性的怪（原版 SomeAnimal 那一类）照样能打，只是排序时按 0 算
        if category != 'monster':
            continue
        if hp <= 0:
            dropped.append((entity_id, '血量 0 或负，不是能打的怪'))
            continue
        if hp > max_health:
            dropped.append((entity_id, '血量 %s 超过上限 %s，判为打不死（剔出池子）' % (health, max_health)))
            continue
        if at <= 0:
            dropped.append((entity_id, 'attack_damage %s，不会近战也打不掉血（多半是伤害特效实体）' % attack))
            continue
        if any(pattern.search(entity_id) for pattern in exclude_patterns):
            dropped.append((entity_id, '命中效果实体的名字黑名单'))
            continue
        token = effect_shaped(entity_id, class_name)
        if token:
            dropped.append((entity_id, '名字/类形状命中特效实体关键字「%s」（%s），不是独立能打的怪'
                            % (token, class_name.split('.')[-1] or entity_id)))
            continue
        try:
            sp = float(speed)
        except ValueError:
            sp = -1.0
        if sp <= 0 and hp >= 999:
            dropped.append((entity_id, '血量 %s 顶着上限又不会移动，判为特效宿主（%s）'
                            % (health, class_name.split('.')[-1] or '类名读不出来')))
            continue
        rows.append((entity_id, mod, category, hp, armor, at))
    return rows, dropped



def band_edges(rows, bands):
    """按血量升序切档，边界取每档第一条的血量。"""
    ordered = sorted(rows, key=lambda row: (row[3], row[0]))
    size = max(1, len(ordered) // bands)
    edges = []
    for index in range(bands):
        chunk = ordered[index * size:(index + 1) * size if index + 1 < bands else len(ordered)]
        edges.append(chunk)
    return edges, size


def pick_tier_pool(chunk, limit, per_mod=2):
    """档内按血量从高到低取，第一只就是该档的 BOSS 候选（爬塔 BOSS 层取 mobs[0]）。"""
    ordered = sorted(chunk, key=lambda row: (-row[3], -row[5], row[0]))
    picked = []
    seen = {}
    for row in ordered:
        if len(picked) >= limit:
            break
        if seen.get(row[1], 0) >= per_mod:
            continue
        seen[row[1]] = seen.get(row[1], 0) + 1
        picked.append(row[0])
    if len(picked) < limit:  # 单一 mod 的档放宽限制，池子不许空
        for row in ordered:
            if len(picked) >= limit:
                break
            if row[0] not in picked:
                picked.append(row[0])
    return picked


def build_tower(jar_tower, tiers, affix_ids):
    tower = json.loads(jar_tower.read_text(encoding='utf-8'))
    scaling = tower['scaling']
    scaling['tiers'] = tiers
    if affix_ids:
        scaling['boss_affix_pool'] = ['trial_portal:' + name for name in affix_ids[:4]]
    tower['comment'] = ('按怪物血量分档递进的爬塔（档界取自 mob-census.tsv 的真实血量，'
                        '每档第一只是该档最硬的，BOSS 层就取它）')
    return tower


def build_tier(index, chunk, affix_ids):
    span = BAND_SPANS[index]
    pool = pick_tier_pool(chunk, 10)
    tier = {
        'from': span[0],
        'to': span[1],
        'count': 4 + index,
        'boss': index >= 2,
        'mobs': pool,
    }
    strength = dict(BAND_STRENGTH[index])
    if strength:
        tier['strength'] = strength
    if index >= 3 and affix_ids:
        tier['boss_affix_pool'] = ['trial_portal:' + name for name in affix_ids]
    return tier


PER_CHALLENGE = 2


# --------------------------------------------------------------------------- 100 层的爬塔

# 原版 generic.max_health 是 RangedAttribute(20, 1, **1024**)，且 AttributeInstance 会夹基值：
# 血量算出来超过 1024 的那部分不是"更难"，是被静默截平——"100 层递进"会在顶层变成每一层都一样。
# 所以曲线本身封顶（CEILING），推进交给只数 / 护甲 / 击退抗性 / BOSS 词条，不交给越界的眼倍率。
CEILING = 1000.0


def floor_targets(floors, start_hp, end_hp):
    """第 f 层想给玩家的"有效血量"目标：几何上升（前期每层差别明显，后期不被 1024 截平）。"""
    if floors <= 1:
        return [start_hp]
    ratio = (end_hp / start_hp) ** (1.0 / (floors - 1))
    return [round(start_hp * (ratio ** index), 2) for index in range(floors)]


def build_floor_tiers(rows, floors, start_hp, end_hp, affixes, per_mod=3, pool_size=8):
    """每层一段 tier：怪从"基础血量最接近本层目标"的窗口里取，段内第一只最硬（BOSS 层取 mobs[0]）。

    倍率写在本层自己身上（strength.health_multiplier = 目标 ÷ 池子血量中位数），
    全局 health_multiplier_per_floor 因此置 0——两处同时开会把同一份加成乘两遍。
    """
    ordered = sorted(rows, key=lambda row: (row[3], row[0]))
    targets = floor_targets(floors, start_hp, end_hp)
    tiers = []
    table = []
    for index, target in enumerate(targets):
        floor = index + 1
        # 窗口：基础血量离目标最近的若干只（允许相邻层复用同一只怪——血量本来就扎堆，
        # 原版 104 只怪都是 20 血，硬要"每层怪不重样"只会逼生成器去取血量差得远的怪，递进反倒假了）
        window = sorted(ordered, key=lambda row: (abs(row[3] - target), row[0]))[:pool_size * 4]
        pool = []
        seen = {}
        for row in sorted(window, key=lambda row: (-abs(row[3] - target) * 0 + row[3], row[0]), reverse=True):
            if len(pool) >= pool_size:
                break
            if seen.get(row[1], 0) >= per_mod:
                continue
            seen[row[1]] = seen.get(row[1], 0) + 1
            pool.append(row)
        if len(pool) < pool_size:
            for row in sorted(window, key=lambda row: -row[3]):
                if len(pool) >= pool_size or row[0] in [item[0] for item in pool]:
                    continue
                pool.append(row)
        pool.sort(key=lambda row: (-row[3], -row[5], row[0]))
        hp = [row[3] for row in pool]
        median = statistics.median(hp)
        multiplier = round(target / median, 3) if median > 0 else 1.0
        boss = floor % 10 == 0
        tier = {'from': floor, 'to': floor, 'count': min(12, 3 + index // 10), 'boss': boss,
                'mobs': [row[0] for row in pool]}
        strength = {'health_multiplier': multiplier}
        # 护甲与击退抗性只加在早中期：顶层不再加，免得血量之外又叠一层越界
        if floor >= 15:
            strength['armor_bonus'] = min(6.0, round((floor - 10) / 15.0, 2))
        if floor >= 30:
            strength['knockback_resistance'] = round(min(0.6, (floor - 25) / 150.0), 3)
        if floor >= 50:
            strength['damage_multiplier'] = round(min(1.6, 1.0 + (floor - 50) / 60.0), 3)
        tier['strength'] = strength
        if boss and affixes:
            tier['boss_affix_pool'] = ['trial_portal:' + name for name in affixes[:min(len(affixes), 2 + floor // 30)]]
        tiers.append(tier)
        table.append({'floor': floor, 'boss': boss, 'median_hp': median, 'multiplier': multiplier,
                      'effective_hp': round(median * multiplier, 1), 'top_hp': max(hp),
                      'count': tier['count'], 'mobs': tier['mobs'], 'strength': strength})
    tiers[-1]['to'] = 9999  # 100 层之后继续无尽爬：最后一段兜住，取不到内容会静默空场
    return tiers, table


def apply_floor_scaling(tower, floors, table):
    """逐层模式下接管 scaling 的全局倍率：加成已经写在各层自己的 strength 里了。

    不清零就是同一份倍率乘两遍——而且第二遍没人对账：玩家只看到顶层血量忽然爆掉，
    然后被 generic.max_health 的 1024 上限夹平，于是"第 90 层"和"第 100 层"其实一样硬。
    """
    scaling = tower['scaling']
    scaling['health_multiplier_per_floor'] = 0.0
    scaling['damage_multiplier_per_floor'] = 0.005
    scaling['count_per_floor'] = 0.0
    scaling['boss_every_floors'] = 10
    scaling['reward_every_floors'] = 5
    scaling['max_count_per_entry'] = max(item['count'] for item in table)
    tower['comment'] = ('逐层递进的爬塔（%d 层）：每层一段 tiers，本层血量倍率取自 mob-census 的真实血量，'
                        '有效血量从 %s 升到 %s，全程不超原版 1024 上限；'
                        '每 10 层一个 BOSS 层，取该层池子里最硬的怪'
                        % (floors, table[0]['effective_hp'], table[-1]['effective_hp']))


def write_floor_table(prefix, label, table):
    """逐层表：md 给人读，tsv 给机器/表格软件读。两份同源，不各写一份数字。"""
    head = '| 层 | 主力怪（第一只＝BOSS 候选） | 池基础血量中位 | 本层血量倍率 | 有效血量 | 池内最硬 | 每层只数 | BOSS | 其他加成 |'
    lines = ['# %s 爬塔 %d 层（按怪物真实血量递进）' % (label, len(table)), '',
             '数据来源：mob-census.tsv（该整合包注册表实测）。有效血量 = 本层池子血量中位数 × 本层倍率；'
             '原版 `generic.max_health` 上限 1024，所以曲线封顶在 %s，再往上只能靠只数、护甲与 BOSS 词条推进。' % CEILING,
             '', head, '| --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    tsv = ['floor\tboss\tmedian_hp\tmultiplier\teffective_hp\ttop_hp\tcount\tmobs\textra_strength']
    for item in table:
        extra = ', '.join('%s=%s' % (key, value) for key, value in item['strength'].items()
                          if key != 'health_multiplier') or '—'
        lines.append('| %d | %s | %s | ×%s | **%s** | %s | %d | %s | %s |' % (
            item['floor'], '、'.join(item['mobs'][:3]), item['median_hp'], item['multiplier'],
            item['effective_hp'], item['top_hp'], item['count'], 'BOSS' if item['boss'] else '', extra))
        tsv.append('\t'.join(str(value) for value in (
            item['floor'], int(item['boss']), item['median_hp'], item['multiplier'], item['effective_hp'],
            item['top_hp'], item['count'], ';'.join(item['mobs']), extra)))
    pathlib.Path(str(prefix) + '.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    pathlib.Path(str(prefix) + '.tsv').write_text('\n'.join(tsv) + '\n', encoding='utf-8')


def verify_floors(tiers, table, hp_by_id, problems):
    """100 层的账：层号连续、池子非空、ID 都在普查里、有效血量严格上升且不超原版上限。"""
    if tiers[0]['from'] != 1:
        problems.append('第一段不是从第 1 层起')
    if tiers[-1]['to'] != 9999:
        problems.append('最后一段没铺到 9999，无尽爬塔会取不到内容')
    previous = None
    previous_floor = 0
    for entry in table:
        floor = entry['floor']
        if floor != previous_floor + 1:
            problems.append('层号不连续：%d 之后是 %d' % (previous_floor, floor))
        previous_floor = floor
        missing = [mob for mob in entry['mobs'] if hp_by_id.get(mob) is None]
        if missing:
            problems.append('第 %d 层引用了普查里不存在的怪：%s' % (floor, missing[:3]))
        if not entry['mobs']:
            problems.append('第 %d 层池子是空的' % floor)
        if entry['effective_hp'] > CEILING:
            problems.append('第 %d 层有效血量 %s 超了原版上限（1024 会把它夹平，往后每一层都一样硬）'
                            % (floor, entry['effective_hp']))
        if previous is not None and entry['effective_hp'] <= previous:
            problems.append('第 %d 层有效血量没超过上一层（%s → %s）' % (floor, previous, entry['effective_hp']))
        previous = entry['effective_hp']
    bosses = [entry['floor'] for entry in table if entry['boss']]
    if bosses and bosses != list(range(10, len(table) + 1, 10)):
        problems.append('BOSS 层不是每 10 层一个：%s' % bosses[:12])
    return table[0]['effective_hp'], table[-1]['effective_hp']



def pick_capped(chunk, limit, per_mod, multiplier, ceiling=CEILING):
    """按常规取一池怪，但保证「本只基础血量 × 本档倍率」不超原版上限。

    `generic.max_health` 是 RangedAttribute(20, 1, **1024**)，AttributeInstance 还会夹基值：
    超出去的那部分不是"更难"，是被静默夹平。顶阶副本 hard 写 ×1.8 配 1000 血的怪，
    和 555 血的怪其实一模一样硬——而 JSON 里看着是两回事。
    """
    hp = {row[0]: row[3] for row in chunk}
    pool = pick_tier_pool(chunk, limit, per_mod)
    if not pool:
        return [], multiplier, 0.0
    median = statistics.median([hp[name] for name in pool])
    used = multiplier if median <= 0 else min(multiplier, round(ceiling / median, 3))
    kept = [name for name in pool if hp[name] * used <= ceiling]
    if not kept:  # 整池都会被夹：退到本档最软的几只，倍率也跟着压
        softest = sorted(hp.items(), key=lambda item: item[1])[:limit]
        kept = [name for name, _ in softest]
        median = statistics.median([hp[name] for name in kept])
        used = min(multiplier, round(ceiling / median, 3)) if median > 0 else multiplier
        kept = [name for name in kept if hp[name] * used <= ceiling] or kept[:1]
    return kept, used, median


def build_challenge(index, chunks, label, per_mod=PER_CHALLENGE):
    """一份副本三档，档与档之间往上一档走，副本与副本之间也整体往上走。"""
    easy_chunk = chunks[index]
    normal_chunk = chunks[min(index + 1, len(chunks) - 1)]
    hard_chunk = chunks[min(index + 2, len(chunks) - 1)]
    easy, easy_mult, _ = pick_capped(easy_chunk, 6, per_mod, 1.0)
    normal, normal_mult, _ = pick_capped(normal_chunk, 6, per_mod, 1.35)
    hard, hard_mult, _ = pick_capped(hard_chunk, 6, per_mod, 1.8)
    if easy_chunk is normal_chunk is hard_chunk:
        # 顶阶（往上没有更硬的档可取）：三档必须**共用同一份池子**。
        # 各挑各的会倒挂——easy 倍率 1.0 能挑到 950 血的，hard 要 ×1.8 就只能挑 555 血以下的，
        # 于是"选最难"反而打更软的怪（星罗棋布剔掉特效实体之后暴露出来的：[950.0, 842.4, 842.4]）。
        # 共用池子的上限是 1000 ÷ 1.8：三档的有效血量就是同一批基础血量 ×1.0 / ×1.35 / ×1.8，
        # 既严格往上走，又没有一只会被 1024 夹平（夹平=三档打起来一样）。
        fits = [row for row in easy_chunk if row[3] * 1.8 <= CEILING]
        shared = pick_tier_pool(fits, 6, per_mod)
        easy = normal = hard = shared
        easy_mult, normal_mult, hard_mult = 1.0, 1.35, 1.8

    # 血量到顶阶会被 1024 夹平（见 pick_capped），所以另外三条轴按"档内第几难 + 第几阶"稳定上升，
    # 保证就算血量推不动了，hard 也一定比 easy 难——不是靠数字好看，是靠打得动的那些维度
    axis = {'easy': 0, 'normal': 1, 'hard': 2}
    low = int(min(row[3] for row in easy_chunk))
    high = int(max(row[3] for row in easy_chunk))
    difficulties = {}
    caps = {'easy': (easy, easy_mult, 10, 2, 150, REWARD_LADDER[index]),
            'normal': (normal, normal_mult, 14, 3, 120, REWARD_LADDER[min(index + 1, len(REWARD_LADDER) - 1)]),
            'hard': (hard, hard_mult, 18, 4, 95, REWARD_LADDER[min(index + 2, len(REWARD_LADDER) - 1)])}
    for key, (pool, multiplier, cap, batch, interval, rewards) in caps.items():
        step = axis[key]
        entry = {
            'display_name': ['浅层', '中层', '深层'][['easy', 'normal', 'hard'].index(key)],
            'mobs': [{'entity': entity, 'count': 2 if key == 'hard' else 1} for entity in pool],
            'mob_cap': cap,
            'spawn_batch': batch,
            'spawn_interval_ticks': interval,
            'strength': {'health_multiplier': multiplier,
                         'armor_bonus': round(min(8.0, 0.9 * step + 0.18 * index), 2),
                         'damage_multiplier': round(min(2.0, 1.0 + 0.12 * step + 0.02 * index), 3),
                         'knockback_resistance': round(min(0.6, 0.12 * step + 0.02 * index), 3)},
            'kill_rewards': rewards,
        }
        difficulties[key] = entry
    return {
        'comment': ('这一份的怪按血量区间挑：本档怪血量 ' + str(low) + '~' + str(high)
                    + '，三档依次往上一档取怪（数值来自 mob-census.tsv）'),
        'display_name': label + ' ' + BAND_NAMES[index] + ' · 第 ' + str(index + 1) + ' 阶',
        'icon_item': ICONS[index % len(ICONS)],
        'order': 100 + index * 10,
        'duration_ticks': 6000,
        'difficulties': difficulties,
    }


def build_defense(index, bands_used, chunks, label, affixes, per_mod=2):
    """一场守塔关卡：怪从第 index 档的池子里挑，波数、塔血、每口伤害都按关卡序号往上走。

    与副本那三份的分工：守塔的"难度"不只来自怪的强度，还来自**塔有多不禁打**与**多少比例的怪直奔塔**，
    所以三个旋钮一起递进，避免出现"名字不同、打起来一模一样"。
    """
    chunk = chunks[min(index, len(chunks) - 1)]
    multiplier = 1.0 + 0.35 * index
    pool, used, median = pick_capped(chunk, 4 + (index % 2), per_mod, multiplier)
    if not pool:
        return None
    waves = 3 + index
    tower_health = round(200.0 + 140.0 * index, 1)
    focus = round(min(0.85, 0.4 + 0.08 * index), 2)
    per_hit = round(min(tower_health / 8.0, 2.0 + 1.5 * index), 2)
    ward_radius = min(DefenseLimits.WARD_MAX, 14 + 2 * index)
    built = []
    for wave_index in range(waves):
        last = wave_index == waves - 1
        shift = wave_index % max(1, len(pool))
        mobs = []
        if last and affixes:
            boss = pool[shift]
            mobs.append({'entity': boss, 'count': 1, 'boss': True})
            support = pool[(shift + 1) % len(pool)]
            mobs.append({'entity': support, 'count': 3 + index})
        else:
            for offset in range(2):
                name = pool[(shift + offset) % len(pool)]
                mobs.append({'entity': name, 'count': 2 + wave_index // 2})
        wave = {'title': '第 %d 波 · %s' % (wave_index + 1,
                                           '收尾' if last else ['压阵', '试探', '两翼', '消耗', '破城'][wave_index % 5]),
                'mobs': mobs}
        if wave_index % 2 == 1:
            wave['strength'] = {'speed_multiplier': round(1.0 + 0.06 * wave_index, 2)}
        if last:
            wave['rewards'] = REWARD_LADDER[min(index, len(REWARD_LADDER) - 1)]
        built.append(wave)
    low = int(min(row[3] for row in chunk))
    high = int(max(row[3] for row in chunk))
    definition = {
        'comment': ('守塔第 %d 关：怪取自血量区间 %d~%d 的那一档（%d 种里挑 %d 种，本池血量中位数 %s，'
                    '倍率 %s），波数 %d、塔血 %s、拆塔怪比例 %s、每口 %s') % (
                index + 1, low, high, len(chunk), len(pool), round(median, 1), used,
                waves, tower_health, focus, per_hit),
        'display_name': label + '守塔 · ' + BAND_NAMES[min(index, len(BAND_NAMES) - 1)] + '第 ' + str(index + 1) + ' 关',
        'icon_item': ICONS[index % len(ICONS)],
        'order': 300 + index * 10,
        'ward_radius': ward_radius,
        'tower_health': tower_health,
        'intermission_ticks': max(60, 260 - 20 * index),
        'tower_focus_share': focus,
        'tower_damage_per_hit': per_hit,
        'tower_hit_interval_ticks': 20,
        'strength': {'health_multiplier': used,
                     'armor_bonus': round(min(8.0, 0.9 * index), 2),
                     'damage_multiplier': round(min(2.0, 1.0 + 0.15 * index), 3)},
        'waves': built,
        'rewards': REWARD_LADDER[min(index, len(REWARD_LADDER) - 1)],
        'boss_affix_pool': affixes[:4] if affixes else [],
    }
    return definition


class DefenseLimits:
    """DefenseDefinition#resolve 里那几个边界，脚本这边要提前对齐，别等校验器报错才知道。"""
    WARD_MIN = 8
    WARD_MAX = 30
    MAX_ENTRIES = 12


def verify_defenses(defenses, hp_by_id, report, problems):
    """关卡之间的递进必须是**数出来的**：塔血、波数、血量倍率三条都得往上走。

    这条不是装饰：分档按索引切，两档遇到大片同血量的怪就会生成两件事别无异的关卡，
    选单上看着是两关、打起来是一关。
    """
    if not defenses:
        return
    previous = None
    for name, definition in defenses:
        entries = sum(len(wave['mobs']) for wave in definition['waves'])
        if entries > DefenseLimits.MAX_ENTRIES:
            problems.append(name + '：单波条目数超过 ' + DefenseLimits.MAX_ENTRIES)
        total = sum(mob['count'] for wave in definition['waves'] for mob in wave['mobs'])
        mult = definition['strength']['health_multiplier']
        weak = [mob['entity'] for wave in definition['waves'] for mob in wave['mobs']
                if hp_by_id.get(mob['entity'], 0) * mult > CEILING]
        if weak:
            problems.append(name + '：这些怪 ×倍率会撞原版 1024 上限被夹平 ' + ', '.join(weak[:4]))
        row = (name, definition['tower_health'], len(definition['waves']), round(mult, 3), total)
        report.append('  守塔 %s：塔血 %s · %d 波 · 倍率 %s · 共 %d 只' % row)
        if previous is not None:
            if row[1] <= previous[1]:
                problems.append(name + '：塔血没有比上一关高（%s ≤ %s）' % (row[1], previous[1]))
            if row[2] <= previous[2]:
                problems.append(name + '：波数没有比上一关多（%d ≤ %d）' % (row[2], previous[2]))
            if row[3] <= previous[3]:
                problems.append(name + '：血量倍率没有比上一关高（%s ≤ %s）' % (row[3], previous[3]))
        previous = row


def audit_output_shapes(data_dir):
    """出包之后再扫一遍：三层内容（爬塔 tiers/floors、副本三档、守塔各波）里只要还留着一个
    形状像特效/召唤物的 id，就当场失败。

    这条不是重复劳动：`read_census` 那五道判据挡的是"进池子之前"，而池子还会被 `--add-exclude`
    覆盖、被以后的普查新模组绕过。按**最终产物**扫才叫约束——红的时候直接指出是哪一份 JSON 的哪一段。
    """
    offenders = []
    total = set()

    def take(entity_id, where):
        total.add(entity_id)
        token = effect_shaped(entity_id)
        if token:
            offenders.append('%s 引用了 %s（命中关键字「%s」）' % (where, entity_id, token))

    tower = data_dir / 'trials/tower.json'
    if tower.exists():
        scaling = json.loads(tower.read_text(encoding='utf-8')).get('scaling') or {}
        for seg in scaling.get('tiers') or []:
            for mob in seg.get('mobs') or []:
                take(mob if isinstance(mob, str) else mob['entity'],
                     'tower.json 第 %s-%s 层' % (seg.get('from'), seg.get('to')))
        for floor in scaling.get('floors') or []:
            for mob in floor.get('mobs') or []:
                take(mob['entity'], 'tower.json 旧层段 %s' % floor.get('floor'))
    for path in sorted((data_dir / 'challenges').glob('*.json')):
        doc = json.loads(path.read_text(encoding='utf-8'))
        for diff, body in (doc.get('difficulties') or {}).items():
            for mob in body.get('mobs') or []:
                take(mob['entity'], '%s 的 %s 档' % (path.name, diff))
    for path in sorted((data_dir / 'defenses').glob('*.json')) if (data_dir / 'defenses').exists() else []:
        doc = json.loads(path.read_text(encoding='utf-8'))
        for index, wave in enumerate(doc.get('waves') or []):
            for mob in wave.get('mobs') or []:
                take(mob['entity'], '%s 的第 %d 波' % (path.name, index + 1))
    return offenders, len(total)


def affix_names(data_dir):
    folder = data_dir / 'boss_affixes'
    if not folder.exists():
        return []
    return sorted(path.stem for path in folder.glob('*.json'))


def validate(staging_data):
    """拿工程自带的离线校验器扫一遍生成物：字段齐不齐、引用自不自洽、奖励有没有空条目。

    JAVA_HOME 由调用方给（本机不钉 JDK17 时新 daemon 会崩在 major version 71），这里不造环境变量。
    """
    gradlew = PROJECT / ('gradlew.bat' if sys.platform.startswith('win') else 'gradlew')
    proc = subprocess.run([str(gradlew), 'checkTrialData', '--offline', '--args='
                           + str(staging_data)], cwd=str(PROJECT), capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=1800)
    tail = (proc.stdout or '') + (proc.stderr or '')
    return proc.returncode, tail


def verify_ladder(tiers, challenges, hp_by_id, report):
    """把"难度逐渐提升"变成会自己变红的账：档与档之间血量中位数必须严格上升。

    这条不是装饰——分档是按索引切的，遇到大片同血量的怪（原版一堆 20 血）相邻档可能一模一样，
    那时候生成的"递进"是假的。校验不过就退回非零，交付不出去。
    """
    problems = []
    medians = []
    for index, tier in enumerate(tiers):
        pool = [hp_by_id.get(entity) for entity in tier['mobs']]
        if any(value is None for value in pool):
            problems.append('层段 %s-%s 引用了普查里不存在的怪' % (tier['from'], tier['to']))
            continue
        if not pool:
            problems.append('层段 %s-%s 池子是空的' % (tier['from'], tier['to']))
            continue
        medians.append(statistics.median(pool))
    for index in range(1, len(medians)):
        if medians[index] <= medians[index - 1]:
            problems.append('爬塔层段 %d 的血量中位数没有超过上一层段（%s → %s）'
                            % (index + 1, medians[index - 1], medians[index]))
    for name, challenge in challenges.items():
        steps = []
        axes = []
        for key in ('easy', 'normal', 'hard'):
            difficulty = challenge['difficulties'][key]
            pool = [hp_by_id.get(entry['entity']) for entry in difficulty['mobs']]
            if any(value is None for value in pool):
                problems.append(name + '/' + key + ' 引用了普查里不存在的怪')
                continue
            # 有效血量：怪本身血量 ×这一档的强度倍率。顶档的副本三档取不到更高的怪，
            # 但倍率还在往上走，那才是"逐渐提升"真正作用在玩家身上的数字。
            multiplier = difficulty.get('strength', {}).get('health_multiplier', 1.0)
            ceilinged = [round(min(1024.0, hp_by_id[e['entity']] * multiplier), 1)
                         for e in difficulty['mobs'] if hp_by_id.get(e['entity'])]
            other_axes = (difficulty['mob_cap'], difficulty['spawn_batch'],
                          difficulty.get('strength', {}).get('armor_bonus', 0),
                          difficulty.get('strength', {}).get('damage_multiplier', 1))
            over = [entity['entity'] for entity in difficulty['mobs']
                    if hp_by_id.get(entity['entity']) and hp_by_id[entity['entity']] * multiplier > CEILING]
            if over:
                problems.append('%s/%s 有 %d 只怪乘完倍率超原版 1024（如 %s 算出来 %s）——顶不动了，剩下的只是数字好看' % (name, key, len(over), over[0],
                                round(hp_by_id[over[0]] * multiplier, 1)))
            steps.append(round(statistics.median(ceilinged), 1) if ceilinged else 0.0)
            axes.append(other_axes)
        if not (steps[0] <= steps[1] <= steps[2]):
            problems.append(name + ' 三档实际血量（按原版 1024 夹平后）倒退了：%s' % steps)
        # 血量到顶阶推不动是原版的天花板，不是生成器的错；但那时候必须有别的轴在涨，
        # 否则三档就是同一场战斗换了个名字——那才是"配了等于没配"
        health_rises = steps[0] < steps[2]
        other_rises = all(axes[2][k] >= axes[1][k] and axes[2][k] > axes[0][k] for k in range(4))
        if not (health_rises or other_rises):
            problems.append(name + ' 三档一模一样（血量已顶到 1024，护甲/伤害/只数也没往上加）：%s' % steps)
        report.append('  %s 三档实际血量中位数 %s%s' % (
            name, steps, '' if health_rises else '（血量到顶，靠护甲/伤害/只数推进）'))
    report.append('  爬塔各段血量中位数 ' + ' → '.join(str(value) for value in medians))
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--census', default='run/mob-census.tsv')
    parser.add_argument('--out', default='build/nw-ladder')
    parser.add_argument('--bands', type=int, default=6)
    parser.add_argument('--seed', type=int, default=20261008)
    parser.add_argument('--max-health', type=float, default=5000,
                        help='血量超过这个数判为打不死，剔出池子并留痕')
    parser.add_argument('--id-prefix', default='pack', help='生成的副本文件名前缀（如 nw / xb）')
    parser.add_argument('--add-exclude', default='', help='在上面默认名单之外再补的正则（逗号分隔）')
    parser.add_argument('--per-mod', type=int, default=2, help='一档里每个 mod 最多占几只（防一个包占满整档）')
    parser.add_argument('--exclude-pattern', default=DEFAULT_EXCLUDES,
                        help='逗号分隔的正则，命中即剔；默认是工具里那份"注册成实体的伤害特效"名单，'
                             '自己再补的用 --add-exclude')
    parser.add_argument('--label', default='整合包', help='写进 comment 与副本名的包名')
    parser.add_argument('--floors', type=int, default=0,
                        help='爬塔做成几层（0 = 沿用按档分段的旧形状；给 100 就是每层一段、逐层递进）')
    parser.add_argument('--start-hp', type=float, default=12.0, help='第 1 层想给玩家的有效血量')
    parser.add_argument('--end-hp', type=float, default=900.0,
                        help='最后一层想给玩家的有效血量（必须 < 1024，原版 generic.max_health 的上限）')
    parser.add_argument('--emit-table', default='',
                        help='把逐层表另写一份给人看：给个前缀路径，生成 <前缀>.md 与 <前缀>.tsv')
    parser.add_argument('--defenses', type=int, default=0,
                        help='顺带生成几场守塔关卡（0 = 不生成）。关卡之间按塔血/波数/倍率三条一起递进，'
                             '对不上的话脚本直接不出包')
    parser.add_argument('--overlay', default='',
                        help='同时把生成物写进工程内的整合包叠加层 packOverlays/<名字>/，'
                             '这样 -PpackOverlay=<名字> 构建出的 jar 自带内容，新建世界开箱就有')
    args = parser.parse_args()

    raws = [args.exclude_pattern, args.add_exclude]
    patterns = [re.compile(item.strip()) for raw in raws for item in raw.split(',') if item.strip()]
    rows, dropped = read_census(args.census, args.max_health, patterns)
    if not rows:
        print('普查里没有可用的怪物条目（category=monster 且血量>0）')
        return 1
    random.seed(args.seed)
    bands = min(args.bands, max(1, len(rows) // 3))
    chunks, size = band_edges(rows, bands)
    print('可用怪物 %d 种，切成 %d 档，每档约 %d 种' % (len(rows), bands, size))
    for index, chunk in enumerate(chunks):
        if not chunk:
            print('  第 %d 档空了——数据太少，缩小档数重来' % (index + 1))
            return 1
        print('  档 %d（血量 %s~%s，%d 种，第 %d 阶=%s）：%s' % (
            index + 1, int(chunk[0][3]), int(chunk[-1][3]), len(chunk), index + 1,
            BAND_NAMES[index], ', '.join(row[0] for row in chunk[:3])))
    if dropped:
        print('被剔掉的条目：')
        for entity_id, reason in dropped[:10]:
            print('   ', entity_id, '·', reason)
        if len(dropped) > 10:
            print('    （其余 %d 条同类，全量在普查文件里可按 mod 复盘）' % (len(dropped) - 10))

    jar_data = PROJECT / 'src/main/resources/data/trial_portal'
    affixes = affix_names(jar_data)
    hp_by_id = {row[0]: row[3] for row in rows}
    floor_table = []
    if args.floors:
        tiers, floor_table = build_floor_tiers(rows, args.floors, args.start_hp, args.end_hp,
                                               affixes, args.per_mod + 1)
        print('逐层爬塔：%d 层，有效血量 %s → %s（原版上限 1024，封顶 %s）'
              % (args.floors, floor_table[0]['effective_hp'], floor_table[-1]['effective_hp'], CEILING))
    else:
        tiers = [build_tier(index, chunk, affixes) for index, chunk in enumerate(chunks)]
        # 层段区间要接得上：最后一档覆盖到 9999，否则无尽爬塔取不到内容
        tiers[-1]['to'] = 9999
        tiers[0]['from'] = 1

    out = pathlib.Path(args.out)
    pack = out / ('trial_portal_' + args.id_prefix + '_ladder')
    data = pack / 'data' / 'trial_portal'
    if pack.exists():
        shutil.rmtree(pack)
    (data / 'challenges').mkdir(parents=True)
    (data / 'trials').mkdir(parents=True)
    if args.defenses:
        (data / 'defenses').mkdir(parents=True)
    pack.joinpath('pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 15,
                  'description': 'trial_portal 递进难度：按 mob-census 的真实血量分档'}},
        ensure_ascii=False, indent=2), encoding='utf-8')

    challenges = {}
    for index, chunk in enumerate(chunks):
        challenges[args.id_prefix + '_band_' + str(index + 1)] = build_challenge(index, chunks,
                                                                                args.label, args.per_mod)
    defenses = {}
    problems = []  # 守塔挑不出怪时下面就要记账，不能等到 verify_ladder 才第一次赋值
    for index in range(args.defenses):
        built = build_defense(index, args.defenses, chunks, args.label, affixes, args.per_mod)
        if built is None:
            problems.append('守塔第 %d 关挑不出怪（这一档的池子是空的）' % (index + 1))
            continue
        defenses[args.id_prefix + '_ward_' + str(index + 1)] = built
    report = []
    # 逐层模式下，爬塔那一段由 verify_floors 按"每一层的有效血量"算账；
    # verify_ladder 只留着管副本三档，两把尺各量各的东西
    problems += verify_ladder([] if args.floors else tiers, challenges, hp_by_id, report)
    if args.floors:
        verify_floors(tiers, floor_table, hp_by_id, problems)
    if defenses:
        report.append('守塔关卡（塔血 / 波数 / 倍率三条都要往上走）：')
        verify_defenses(list(defenses.items()), hp_by_id, report, problems)
    for line in report:
        print(line)
    if problems:
        print('递进不成立，先不出包：')
        for line in problems:
            print('  ✗', line)
        return 2

    tower = build_tower(jar_data / 'trials/tower.json', tiers, affixes)
    if args.floors:
        apply_floor_scaling(tower, args.floors, floor_table)
    (data / 'trials/tower.json').write_text(json.dumps(tower, ensure_ascii=False, indent=2),
                                            encoding='utf-8')
    for name, challenge in challenges.items():
        (data / 'challenges' / (name + '.json')).write_text(
            json.dumps(challenge, ensure_ascii=False, indent=2), encoding='utf-8')
    for name, defense in defenses.items():
        (data / 'defenses' / (name + '.json')).write_text(
            json.dumps(defense, ensure_ascii=False, indent=2), encoding='utf-8')
    offenders, pool_size = audit_output_shapes(data)
    if offenders:
        print('出包里还留着非战斗实体（%d 处），不往叠加层/实例里铺：' % len(offenders))
        for line in offenders[:12]:
            print('  ✗', line)
        if len(offenders) > 12:
            print('   （其余 %d 处同类）' % (len(offenders) - 12))
        return 3
    print('形状复查通过：出包 %d 个怪 id，没有一个命中特效/召唤物判据' % pool_size)
    print('写出 datapack：', pack)
    if defenses:
        print('  守塔关卡 %d 场：%s' % (len(defenses), ', '.join(defenses)))

    if args.overlay:
        overlay_root = PROJECT / 'packOverlays' / args.overlay
        if overlay_root.exists():
            shutil.rmtree(overlay_root)
        (overlay_root / 'data' / 'trial_portal').mkdir(parents=True)
        shutil.copytree(data / 'challenges', overlay_root / 'data' / 'trial_portal' / 'challenges')
        shutil.copytree(data / 'trials', overlay_root / 'data' / 'trial_portal' / 'trials')
        if defenses:
            # 文件名各带前缀，盖在工程自带的那几场之上是"再加几关"，不是把 first_ward 顶掉
            shutil.copytree(data / 'defenses', overlay_root / 'data' / 'trial_portal' / 'defenses')
        print('叠加层（构建时 -PpackOverlay=%s 才进 jar）：' % args.overlay, overlay_root)

    # 逐层表要写在叠加层之后：`--emit-table packOverlays/xb/爬塔100层` 是常见用法，
    # 而上面那次 rmtree 会把先写进去的 .md/.tsv 一起带走（先写就得事后手工补拷一次）。
    if args.floors and args.emit_table:
        write_floor_table(pathlib.Path(args.emit_table), args.label, floor_table)
        print('逐层表：', args.emit_table + '.md / ' + args.emit_table + '.tsv')

    # 复扫：把生成物盖在工程自带内容之上，用同一套模型类跑一遍
    # 离线校验器把「目录名」当命名空间（报错里是 staging-data:tower），所以这层必须叫 trial_portal
    staging_root = out / 'staging' / 'data'
    staging = staging_root / 'trial_portal'
    if (out / 'staging').exists():
        shutil.rmtree(out / 'staging')
    staging_root.mkdir(parents=True)
    shutil.copytree(jar_data, staging)
    shutil.copytree(data / 'challenges', staging / 'challenges', dirs_exist_ok=True)
    shutil.copy2(data / 'trials/tower.json', staging / 'trials/tower.json')
    if defenses:
        shutil.copytree(data / 'defenses', staging / 'defenses', dirs_exist_ok=True)
    code, text = validate(staging)
    for line in [l for l in text.splitlines() if '副本' in l or 'trial_portal' in l or 'FAILURE' in l]:
        print('  校验：', line[:200])
    if code != 0:
        report = out / 'validate-failure.log'
        report.write_text(text, encoding='utf-8')
        print('离线校验没过（exit %d），生成物不算交付；校验器原文在 %s' % (code, report))
        return code
    print('离线校验通过：字段齐、引用自洽、层段覆盖到 9999')
    return 0


if __name__ == '__main__':
    sys.exit(main())
