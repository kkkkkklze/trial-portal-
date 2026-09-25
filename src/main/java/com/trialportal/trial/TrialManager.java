package com.trialportal.trial;

import com.mojang.logging.LogUtils;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.boss.PlayerProfile;
import com.trialportal.config.TrialConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 核心：会话状态机 + 生成 + 判定 + 发奖 + 爬塔存档。
 * <p>
 * 存档里只有爬塔记录（{@code tower_floors}）；进行中的会话是内存态——服务器重启即失效，
 * 与「掉线作废」的口径一致。
 */
public class TrialManager extends SavedData
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "trial_portal";
    private static final String TAG_FLOORS = "tower_floors";

    public enum Outcome
    {
        /** 结算离场：发奖 */
        SETTLED,
        /** 放弃 / 死亡 / 中止：按配置可能清空待结算奖励 */
        ABANDONED
    }

    private final Map<UUID, TrialSession> sessions = new HashMap<>();
    /** 死亡时配置为「不清空」的奖励转存处，下一场开局并入（不跨重启）。 */
    private final Map<UUID, List<TrialDefinition.RewardEntry>> carriedOver = new HashMap<>();
    /** 玩家 UUID → 已通过的最高层数。 */
    private final Map<UUID, Integer> towerFloors = new LinkedHashMap<>();
    private int tickCounter;

    public static TrialManager get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(TrialManager::load, TrialManager::new, DATA_NAME);
    }

    public static TrialManager load(CompoundTag tag)
    {
        TrialManager manager = new TrialManager();
        if (tag.contains(TAG_FLOORS))
        {
            CompoundTag floors = tag.getCompound(TAG_FLOORS);
            for (String key : floors.getAllKeys())
            {
                try
                {
                    manager.towerFloors.put(UUID.fromString(key), floors.getInt(key));
                }
                catch (IllegalArgumentException ignored)
                {
                    // 记录里的 UUID 坏了就直接丢掉，别让整份存档读不出来
                }
            }
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        CompoundTag floors = new CompoundTag();
        this.towerFloors.forEach((uuid, floor) -> floors.putInt(uuid.toString(), floor));
        tag.put(TAG_FLOORS, floors);
        return tag;
    }

    // ------------------------------------------------------------------ 查询

    @Nullable
    public TrialSession sessionOf(UUID playerId)
    {
        return this.sessions.get(playerId);
    }

    public boolean hasSession(UUID playerId)
    {
        return this.sessions.containsKey(playerId);
    }

    public int recordedFloor(UUID playerId)
    {
        return this.towerFloors.getOrDefault(playerId, 0);
    }

    public void setRecordedFloor(UUID playerId, int floor)
    {
        if (floor <= 0)
        {
            this.towerFloors.remove(playerId);
        }
        else
        {
            this.towerFloors.put(playerId, floor);
        }
        this.setDirty();
    }

    // ------------------------------------------------------------- 开局与交互

    /**
     * @param origin 生成中心与放弃判定中心
     * @return false 表示没开成（已有一场 / 取不到波次内容），原因已通过 feedback 告知
     */
    public boolean start(ServerPlayer player, TrialDefinition definition, BlockPos origin, int cell)
    {
        if (this.sessions.containsKey(player.getUUID()))
        {
            TrialFeedback.failure(player, "你已经有一场试炼在进行中");
            return false;
        }
        TrialSession session = new TrialSession(player.getUUID(), player.getScoreboardName(), definition.id(),
                player.level().dimension(), origin);
        session.cell = cell;

        if (definition.isEndless())
        {
            int offset = TrialConfig.TOWER_RESUME_OFFSET.get();
            int floor = Math.max(1, recordedFloor(player.getUUID()) + 1 + offset);
            session.waveIndex = floor - 1;
        }

        this.sessions.put(player.getUUID(), session);
        List<TrialDefinition.RewardEntry> carried = this.carriedOver.remove(player.getUUID());
        if (carried != null && !carried.isEmpty())
        {
            session.addReward(carried);
            TrialFeedback.chat(player, "[试炼] 上一场攒下的奖励已并入本场待结算池");
        }
        if (!beginWave(player, session, definition))
        {
            this.sessions.remove(player.getUUID());
            return false;
        }
        TrialFeedback.announce(player,
                definition.displayName + " · 开始",
                definition.isEndless() ? "第 " + session.waveNumber() + " 层" : "共 " + definition.finiteWaveCount() + " 波",
                "[试炼] " + definition.displayName + " 开始");
        return true;
    }

    /** 站在传送门前右键：继续 / 结算。会话绑在玩家身上，所以任意一个自家传送门都能操作当前会话。 */
    public void onPortalUse(ServerPlayer player, boolean sneaking)
    {
        TrialSession session = this.sessions.get(player.getUUID());
        if (session == null)
        {
            return;
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        if (definition == null)
        {
            TrialFeedback.failure(player, "这份试炼已从数据包里移除，无法继续");
            return;
        }
        switch (session.phase)
        {
            case AWAITING_DECISION ->
            {
                if (sneaking)
                {
                    end(player, session, definition, Outcome.SETTLED);
                }
                else
                {
                    session.phase = TrialSession.Phase.INTERMISSION;
                    session.intermissionTicksLeft = intermissionFor(definition, session);
                    TrialFeedback.actionbar(player, "下一波即将开始");
                }
            }
            case INTERMISSION ->
            {
                if (sneaking)
                {
                    end(player, session, definition, Outcome.SETTLED);
                }
                else
                {
                    TrialFeedback.actionbar(player, "下一波即将开始（潜行右键可结算离场）");
                }
            }
            default -> TrialFeedback.actionbar(player, "本波还没打完");
        }
    }

    /** /tei trial stop：管理员语义，不发奖。 */
    public void stop(ServerPlayer player)
    {
        TrialSession session = this.sessions.remove(player.getUUID());
        if (session == null)
        {
            TrialFeedback.failure(player, "当前没有进行中的试炼");
            return;
        }
        session.pending.clear();
        clearMobs(player.server.getLevel(session.dimension), session);
        TrialFeedback.chat(player, "[试炼] 已中止，未发放奖励");
        com.trialportal.arena.ArenaService.returnHome(player);
    }

    public void onPlayerDeath(ServerPlayer player)
    {
        TrialSession session = this.sessions.remove(player.getUUID());
        if (session == null)
        {
            return;
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        if (definition == null)
        {
            return;
        }
        boolean clearsPending = switch (definition.mode)
        {
            case CHALLENGE -> TrialConfig.CHALLENGE_DEATH_CLEARS_PENDING.get();
            case TOWER -> TrialConfig.TOWER_DEATH_CLEARS_PENDING.get();
            case TRIAL -> TrialConfig.TRIAL_DEATH_CLEARS_PENDING.get();
        };
        if (!clearsPending && !session.pending.isEmpty())
        {
            // 不清空 = 下一场接着用：会话已经没了，奖励只能转存到「上次攒下的」池子里
            this.carriedOver.put(player.getUUID(), new ArrayList<>(session.pending));
            session.pending.clear();
        }
        if (definition.isEndless())
        {
            onTowerDeath(player, session);
        }
        if (TrialConfig.GENERAL_CLEAR_MOBS_ON_END.get())
        {
            clearMobs(player.server.getLevel(session.dimension), session);
        }
        TrialFeedback.announce(player, "试炼失败",
                clearsPending ? "累计奖励已清空" : "累计奖励留到下一场",
                "[试炼] 你死了 · " + definition.displayName);
        // 死亡由原版重生流程送玩家回去，这里只拆隔间、清 cell 记录
        com.trialportal.arena.ArenaService.releaseCell(
                com.trialportal.arena.ArenaService.level(player.server), player.getUUID());
    }

    private void onTowerDeath(ServerPlayer player, TrialSession session)
    {
        UUID uuid = player.getUUID();
        int reached = session.waveNumber() - 1;
        if (!TrialConfig.TOWER_KEEP_RECORD_ON_DEATH.get())
        {
            setRecordedFloor(uuid, 0);
            return;
        }
        int penalty = TrialConfig.TOWER_DEATH_FLOOR_PENALTY.get();
        int kept = Math.max(recordedFloor(uuid), reached);
        if (penalty > 0)
        {
            setRecordedFloor(uuid, Math.max(0, kept - penalty));
        }
        else
        {
            recordFloor(uuid, reached);
        }
    }

    // ----------------------------------------------------------------- tick

    public void tick(MinecraftServer server)
    {
        this.tickCounter++;
        int interval = Math.max(1, TrialConfig.GENERAL_TICK_INTERVAL.get());
        if (this.tickCounter % interval != 0)
        {
            return;
        }
        for (TrialSession session : new ArrayList<>(this.sessions.values()))
        {
            step(server, session, interval);
        }
    }

    private void step(MinecraftServer server, TrialSession session, int interval)
    {
        ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
        if (player == null)
        {
            // 掉线：只做清怪 + 丢会话 + 拆隔间，不走结算流程（不发奖也不提示）
            clearMobs(server.getLevel(session.dimension), session);
            this.sessions.remove(session.playerId);
            com.trialportal.arena.ArenaService.releaseCell(com.trialportal.arena.ArenaService.level(server), session.playerId);
            return;
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        if (definition == null)
        {
            end(player, session, null, Outcome.ABANDONED);
            TrialFeedback.failure(player, "试炼定义已被移除，本场中止");
            return;
        }
        ServerLevel level = server.getLevel(session.dimension);
        if (level == null || !level.isLoaded(session.origin))
        {
            return; // 原点区块没加载，跳过本轮：否则会把「没加载」误判成「已清完」
        }
        if (!isInsideArena(player, session, definition))
        {
            end(player, session, definition, Outcome.ABANDONED);
            TrialFeedback.announce(player, "试炼放弃", "已离开试炼范围", "[试炼] 你离开了范围，累计奖励作废");
            return;
        }
        pruneDeadMobs(level, session);
        switch (session.phase)
        {
            case IN_WAVE ->
            {
                if (session.aliveMobs.isEmpty())
                {
                    onWaveCleared(player, session, definition);
                }
            }
            case INTERMISSION ->
            {
                session.intermissionTicksLeft -= interval;
                if (session.intermissionTicksLeft <= 0)
                {
                    advance(player, session, definition);
                }
                else
                {
                    TrialFeedback.actionbar(player, "下一波 " + (session.intermissionTicksLeft / 20 + 1) + " 秒后来");
                }
            }
            case AWAITING_DECISION ->
            {
                // 什么都不做，等右键
            }
        }
    }

    private void onWaveCleared(ServerPlayer player, TrialSession session, TrialDefinition definition)
    {
        TrialDefinition.WaveContent content = definition.waveFor(session.waveIndex);
        if (content != null)
        {
            session.addReward(content.rewards);
        }
        if (definition.isEndless())
        {
            recordFloor(player.getUUID(), session.waveNumber());
        }
        TrialDefinition.WaveContent next = definition.waveFor(session.waveIndex + 1);
        if (next == null)
        {
            end(player, session, definition, Outcome.SETTLED);
            TrialFeedback.announce(player, definition.displayName + " · 通关", "奖励已发放",
                    "[试炼] 通关 " + definition.displayName);
            return;
        }
        if (definition.mode.isPlayerDriven())
        {
            session.phase = TrialSession.Phase.AWAITING_DECISION;
            TrialFeedback.announce(player, "第 " + session.waveNumber() + " 波清完", "右键继续 · 潜行右键结算离场",
                    "[试炼] 等待你的决定");
        }
        else
        {
            session.phase = TrialSession.Phase.INTERMISSION;
            session.intermissionTicksLeft = intermissionFor(definition, session);
        }
    }

    private void advance(ServerPlayer player, TrialSession session, TrialDefinition definition)
    {
        session.waveIndex++;
        if (!beginWave(player, session, definition))
        {
            end(player, session, definition, Outcome.SETTLED);
        }
    }

    private boolean beginWave(ServerPlayer player, TrialSession session, TrialDefinition definition)
    {
        TrialDefinition.WaveContent content = definition.waveFor(session.waveIndex);
        if (content == null)
        {
            return false;
        }
        ServerLevel level = player.server.getLevel(session.dimension);
        if (level == null)
        {
            return false;
        }
        session.aliveMobs.clear();
        session.bossAffixes.clear();
        session.phase = TrialSession.Phase.IN_WAVE;

        int budget = Math.min(TrialConfig.GENERAL_MAX_SPAWNS_PER_WAVE.get(), TrialConfig.GENERAL_MAX_ALIVE_MOBS.get());
        int spawned = 0;
        for (TrialDefinition.MobEntry entry : content.mobs)
        {
            int wanted = Math.max(1, entry.count);
            int room = budget - spawned;
            if (room <= 0)
            {
                LOGGER.warn("[trial_portal] 第 {} 波达到生成上限（{}），剩余的 {} 只被丢弃（不会在下一波补）",
                        session.waveNumber(), budget, entry.entity);
                break;
            }
            int take = Math.min(wanted, room);
            for (int i = 0; i < take; i++)
            {
                if (spawnOne(level, player, session, definition, content, entry))
                {
                    spawned++;
                }
            }
        }
        String title = content.title == null ? "第 " + session.waveNumber() + " 波" : content.title;
        TrialFeedback.title(player, title.replace("%s", player.getScoreboardName()), null);
        return true;
    }

    private boolean spawnOne(ServerLevel level, ServerPlayer owner, TrialSession session, TrialDefinition definition,
                             TrialDefinition.WaveContent content, TrialDefinition.MobEntry entry)
    {
        ResourceLocation typeId = entry.entityId();
        if (typeId == null)
        {
            LOGGER.warn("[trial_portal] entity ID 非法：{}", entry.entity);
            return false;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null)
        {
            LOGGER.warn("[trial_portal] 找不到实体类型 {}", typeId);
            return false;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob mob))
        {
            LOGGER.warn("[trial_portal] {} 不是可生成的生物", typeId);
            return false;
        }
        if (entry.nbt != null && !entry.nbt.isBlank())
        {
            applyNbt(mob, entry.nbt);
        }
        Vec3 position = findSpawnPosition(level, session.origin, definition);
        mob.setPos(position.x, position.y, position.z);
        mob.setYRot(level.getRandom().nextFloat() * 360.0F);

        if (definition.isEndless() && content.floor > 0 && definition.scaling != null)
        {
            scaleForFloor(mob, definition, content.floor);
        }
        if (entry.boss)
        {
            prepareBoss(mob, definition, owner);
            applyBossAffixes(level, mob, definition, content, owner, session);
        }
        else if (mob instanceof Enemy)
        {
            mob.setTarget(owner); // 只有敌对生物会被锁定玩家，生成牛/村民时它们不会主动攻击（设计如此）
        }
        mob.setPersistenceRequired();
        if (!level.addFreshEntity(mob))
        {
            return false;
        }
        session.aliveMobs.add(mob.getUUID());
        return true;
    }

    /**
     * BOSS 待遇：血量补满 + 显名 + 锁定玩家。
     * 词条的实际贴在 {@link #applyBossAffixes}（Slice D），这里只留一个调用点。
     */
    private void prepareBoss(Mob mob, TrialDefinition definition, ServerPlayer owner)
    {
        mob.setHealth(mob.getMaxHealth());
        String name = definition.mode == TrialMode.TRIAL && definition.boss != null && definition.boss.name != null
                ? definition.boss.name.replace("%s", owner.getScoreboardName())
                : owner.getScoreboardName() + " 的对手";
        mob.setCustomName(Component.literal(name));
        mob.setCustomNameVisible(true);
        mob.setTarget(owner);
    }

    /**
     * 用开局瞬间采集的玩家画像去筛词条池，命中的按权重抽样后贴到 BOSS 身上。
     * 池的优先级：本波算出来的（层段专用 → scaling 通用）&gt; boss.affix_pool &gt; 全部已加载词条。
     */
    private void applyBossAffixes(ServerLevel level, Mob mob, TrialDefinition definition,
                                  TrialDefinition.WaveContent content, ServerPlayer owner, TrialSession session)
    {
        List<String> pool = content.affixPool;
        int count = TrialConfig.TRIAL_BOSS_AFFIX_COUNT.get();
        if (definition.boss != null)
        {
            if (pool == null)
            {
                pool = definition.boss.affixPool;
            }
            if (definition.boss.affixCount > 0)
            {
                count = definition.boss.affixCount;
            }
        }
        if (count <= 0)
        {
            return;
        }
        List<BossAffix> picked = BossAffixLibrary.sample(pool, PlayerProfile.of(owner), count,
                TrialConfig.TRIAL_AFFIX_WEIGHT_EXPONENT.get(), level.getRandom());
        if (picked.isEmpty())
        {
            LOGGER.warn("[trial_portal] 玩家 {} 的画像没命中任何词条，BOSS 保持原样", owner.getScoreboardName());
            return;
        }
        BossAffixLibrary.applyTo(mob, picked);
        BossAffixLibrary.decorate(mob, picked);
        for (BossAffix affix : picked)
        {
            session.bossAffixes.add(affix.displayNameOrDefault());
        }
        TrialFeedback.chat(owner, "[试炼] BOSS 克制词条：" + String.join("、", session.bossAffixes));
    }

    private static void applyNbt(Mob mob, String snbt)
    {
        try
        {
            CompoundTag extra = TagParser.parseTag(snbt);
            CompoundTag current = new CompoundTag();
            mob.saveWithoutId(current);
            current.merge(extra);
            mob.load(current);
        }
        catch (Exception exception)
        {
            LOGGER.warn("[trial_portal] 合并 NBT 失败，已忽略：{}（{}）", snbt, exception.getMessage());
        }
    }

    private static void scaleForFloor(Mob mob, TrialDefinition definition, int floor)
    {
        TrialDefinition.ScalingRule scaling = definition.scaling;
        double healthMultiplier = scaling.healthMultiplier(floor);
        double damageMultiplier = scaling.damageMultiplier(floor);
        if (healthMultiplier > 1.0D)
        {
            AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
            health.setBaseValue(health.getBaseValue() * healthMultiplier);
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageMultiplier > 1.0D && damage != null)
        {
            damage.setBaseValue(damage.getBaseValue() * damageMultiplier);
        }
        mob.setHealth(mob.getMaxHealth());
    }

    /** 生成位置是「扫」出来的：16 次随机水平取点，每次从初始高度上下各扫 5 格找落脚位。 */
    private static Vec3 findSpawnPosition(ServerLevel level, BlockPos origin, TrialDefinition definition)
    {
        double radius = Math.max(1.0D, definition.spawnRadiusOrDefault());
        double height = Math.max(1.0D, definition.spawnHeightOrDefault());
        var random = level.getRandom();
        for (int attempt = 0; attempt < 16; attempt++)
        {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = radius * Math.sqrt(random.nextDouble());
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
            for (int dy = (int) Math.ceil(height); dy >= -(int) Math.ceil(height); dy--)
            {
                BlockPos candidate = new BlockPos(x, origin.getY() + dy, z);
                if (level.getBlockState(candidate.below()).isRedstoneConductor(level, candidate.below())
                        && level.isEmptyBlock(candidate) && level.isEmptyBlock(candidate.above()))
                {
                    return Vec3.atBottomCenterOf(candidate);
                }
            }
        }
        return Vec3.atBottomCenterOf(origin.above()); // 兜底：传送门正上方一格，可能卡进方块
    }

    private static void pruneDeadMobs(ServerLevel level, TrialSession session)
    {
        Iterator<java.util.UUID> iterator = session.aliveMobs.iterator();
        while (iterator.hasNext())
        {
            UUID uuid = iterator.next();
            Entity entity = level.getEntity(uuid);
            if (entity == null || (entity instanceof LivingEntity living && !living.isAlive()))
            {
                iterator.remove();
            }
        }
    }

    private static void clearMobs(@Nullable ServerLevel level, TrialSession session)
    {
        if (level == null || !TrialConfig.GENERAL_CLEAR_MOBS_ON_END.get())
        {
            return;
        }
        for (UUID uuid : session.aliveMobs)
        {
            Entity entity = level.getEntity(uuid);
            if (entity != null)
            {
                entity.discard();
            }
        }
        session.aliveMobs.clear();
    }

    private boolean isInsideArena(ServerPlayer player, TrialSession session, TrialDefinition definition)
    {
        double limit = definition.abandonDistanceOrDefault();
        if (limit == 0.0D)
        {
            return true;
        }
        if (limit < 0.0D)
        {
            limit = TrialConfig.GENERAL_DEFAULT_ABANDON_DISTANCE.get();
        }
        ResourceKey<Level> arena = session.dimension;
        if (!player.level().dimension().equals(arena))
        {
            return false; // 换维度 = 放弃
        }
        Vec3 center = Vec3.atCenterOf(session.origin);
        return player.position().distanceToSqr(center) <= limit * limit;
    }

    private int intermissionFor(TrialDefinition definition, TrialSession session)
    {
        int configured = definition.intermissionTicksFor(session.waveIndex);
        return configured < 0 ? TrialConfig.GENERAL_DEFAULT_INTERMISSION_TICKS.get() : configured;
    }

    private void recordFloor(UUID playerId, int clearedFloors)
    {
        if (clearedFloors > recordedFloor(playerId))
        {
            setRecordedFloor(playerId, clearedFloors);
        }
    }

    private void end(ServerPlayer player, TrialSession session, @Nullable TrialDefinition definition, Outcome outcome)
    {
        this.sessions.remove(session.playerId);
        ServerLevel level = player.server.getLevel(session.dimension);
        if (outcome == Outcome.SETTLED && definition != null && definition.isEndless())
        {
            recordFloor(player.getUUID(), session.waveNumber());
        }
        if (outcome == Outcome.SETTLED)
        {
            grantRewards(player, session);
            if (definition != null && definition.isEndless())
            {
                recordFloor(player.getUUID(), session.waveNumber());
            }
        }
        else
        {
            // 放弃 / 中止 / 定义丢失：累计奖励直接作废，没有「留着下一场」这条路
            session.pending.clear();
        }
        clearMobs(level, session);
        com.trialportal.arena.ArenaService.returnHome(player);
    }

    private void grantRewards(ServerPlayer player, TrialSession session)
    {
        if (session.pending.isEmpty())
        {
            TrialFeedback.chat(player, "[试炼] 结算完成（本场没有累计奖励）");
            return;
        }
        boolean toInventory = TrialConfig.GENERAL_REWARD_TO_INVENTORY.get();
        int granted = 0;
        for (TrialDefinition.RewardEntry reward : session.pending)
        {
            ResourceLocation itemId = ResourceLocation.tryParse(reward.item);
            if (itemId == null || ForgeRegistries.ITEMS.getValue(itemId) == null)
            {
                LOGGER.warn("[trial_portal] 奖励物品不存在，已跳过：{}", reward.item);
                continue;
            }
            int remaining = Math.max(1, reward.count);
            while (remaining > 0)
            {
                ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(itemId), Math.min(remaining, 64));
                remaining -= stack.getCount();
                if (toInventory && !player.getInventory().add(stack.copy()))
                {
                    dropAtFeet(player, stack.copy());
                }
                granted += stack.getCount();
            }
        }
        session.pending.clear();
        TrialFeedback.chat(player, String.format(Locale.ROOT, "[试炼] 结算完成，发放 %d 件物品（背包满的部分掉在脚下）", granted));
    }

    private void dropAtFeet(ServerPlayer player, ItemStack stack)
    {
        ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), stack);
        entity.setPickUpDelay(20);
        player.level().addFreshEntity(entity);
    }

    /** /tei trial list 之类的诊断用。 */
    public List<String> activeSessionIds()
    {
        List<String> result = new ArrayList<>();
        this.sessions.values().forEach(session -> result.add(session.playerName + " → " + session.definitionId));
        return result;
    }
}
