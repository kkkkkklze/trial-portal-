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

    三类条目会被剔掉，而且都要留痕（不是静默丢）：
    ① 血量 > max_health——星罗棋布那份里 `powerful_dummy:test_dummy` 是 9.2e18 血的假人，
       NW 那份里两个 999,999,999 血的无敌测试 BOSS，留在池子里那场永远打不完；
    ② attack_damage <= 0——整合包把伤害特效也注册成"怪物类"实体（黑洞、光束、法环、指示器），
       它们既不会走过来也不会掉血，玩家对着空气砍。副作用：原版恶魂那类"伤害写在火球里"的
       远程怪也会被这条剔掉，数量很少，宁可少几只是真的打不动的；
    ③ 命中 exclude_patterns 的 id——同样是效果实体，但 attack 属性照抄了宿主数值，只能按名字剔。
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


def build_challenge(index, chunks, label, per_mod=PER_CHALLENGE):
    """一份副本三档，档与档之间往上一档走，副本与副本之间也整体往上走。"""
    easy = pick_tier_pool(chunks[index], 6, per_mod)
    normal = pick_tier_pool(chunks[min(index + 1, len(chunks) - 1)], 6, per_mod)
    hard = pick_tier_pool(chunks[min(index + 2, len(chunks) - 1)], 6, per_mod)
    low = int(min(row[3] for row in chunks[index]))
    high = int(max(row[3] for row in chunks[index]))
    difficulties = {}
    for key, pool, cap, batch, interval, multiplier, rewards in [
            ('easy', easy, 10, 2, 150, 1.0, REWARD_LADDER[index]),
            ('normal', normal, 14, 3, 120, 1.35, REWARD_LADDER[min(index + 1, len(REWARD_LADDER) - 1)]),
            ('hard', hard, 18, 4, 95, 1.8, REWARD_LADDER[min(index + 2, len(REWARD_LADDER) - 1)])]:
        entry = {
            'display_name': ['浅层', '中层', '深层'][['easy', 'normal', 'hard'].index(key)],
            'mobs': [{'entity': entity, 'count': 2 if key == 'hard' else 1} for entity in pool],
            'mob_cap': cap,
            'spawn_batch': batch,
            'spawn_interval_ticks': interval,
            'strength': {'health_multiplier': multiplier},
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
        for key in ('easy', 'normal', 'hard'):
            difficulty = challenge['difficulties'][key]
            pool = [hp_by_id.get(entry['entity']) for entry in difficulty['mobs']]
            if any(value is None for value in pool):
                problems.append(name + '/' + key + ' 引用了普查里不存在的怪')
                continue
            # 有效血量：怪本身血量 ×这一档的强度倍率。顶档的副本三档取不到更高的怪，
            # 但倍率还在往上走，那才是"逐渐提升"真正作用在玩家身上的数字。
            multiplier = difficulty.get('strength', {}).get('health_multiplier', 1.0)
            steps.append(round(statistics.median(pool) * multiplier, 1))
        if not (steps[0] <= steps[1] <= steps[2] and steps[0] < steps[2]):
            problems.append(name + ' 三档血量没有递进（easy/normal/hard 中位数 = %s）' % steps)
        report.append('  %s 三档血量中位数 %s' % (name, steps))
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
    pack.joinpath('pack.mcmeta').write_text(json.dumps(
        {'pack': {'pack_format': 15,
                  'description': 'trial_portal 递进难度：按 mob-census 的真实血量分档'}},
        ensure_ascii=False, indent=2), encoding='utf-8')

    hp_by_id = {row[0]: row[3] for row in rows}
    challenges = {}
    for index, chunk in enumerate(chunks):
        challenges[args.id_prefix + '_band_' + str(index + 1)] = build_challenge(index, chunks,
                                                                                             args.label, args.per_mod)
    report = []
    problems = verify_ladder(tiers, challenges, hp_by_id, report)
    for line in report:
        print(line)
    if problems:
        print('递进不成立，先不出包：')
        for line in problems:
            print('  ✗', line)
        return 2

    tower = build_tower(jar_data / 'trials/tower.json', tiers, affixes)
    (data / 'trials/tower.json').write_text(json.dumps(tower, ensure_ascii=False, indent=2),
                                            encoding='utf-8')
    for name, challenge in challenges.items():
        (data / 'challenges' / (name + '.json')).write_text(
            json.dumps(challenge, ensure_ascii=False, indent=2), encoding='utf-8')
    print('写出 datapack：', pack)

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
