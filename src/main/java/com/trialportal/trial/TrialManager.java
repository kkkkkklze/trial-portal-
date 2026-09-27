package com.trialportal.trial;

import com.mojang.logging.LogUtils;
import com.trialportal.boss.BossAffix;
import com.trialportal.boss.BossAffixLibrary;
import com.trialportal.boss.PlayerProfile;
import com.trialportal.arena.ArenaService;
import com.trialportal.arena.RoomBuilder;
import com.trialportal.arena.RoomDefinition;
import com.trialportal.arena.RoomLibrary;
import com.trialportal.config.TrialConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
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
    private static final String TAG_CELLS = "arena_cells";
    private static final String TAG_REACH = "arena_reach";
    private static final String TAG_BUILT = "arena_built";
    private static final String TAG_HOMES = "arena_homes";

    public enum Outcome
    {
        /** 主动结算或打完：发奖，爬塔记层 */
        SETTLED,
        /** 走出场地（点返回终端 / 越界离开）：已攒的奖励照发，但不算通关、不记塔层 */
        LEFT,
        /** 管理员中止 / 定义丢失：作废累计奖励 */
        ABANDONED
    }

    private final Map<UUID, TrialSession> sessions = new HashMap<>();
    /** 死亡时配置为「不清空」的奖励转存处，下一场开局并入（不跨重启）。 */
    private final Map<UUID, List<TrialDefinition.RewardEntry>> carriedOver = new HashMap<>();
    /** 玩家 UUID → 已通过的最高层数。 */
    private final Map<UUID, Integer> towerFloors = new LinkedHashMap<>();
    /** 玩家 → 占用的隔间编号。与回返点一起进存档：否则重启后世界里留下没人认领的隔间平台。 */
    private final Map<UUID, Integer> arenaCells = new LinkedHashMap<>();
    /** 玩家 → 本场沿 +Z 建出去的进深。拆场盒子的尺寸要看它，写进存档才不会在调用点上被"忘记传"。 */
    private final Map<UUID, Integer> arenaReach = new LinkedHashMap<>();
    /** 玩家 → 本场真实写过的区域（6 个整数：minX,minY,minZ,maxX,maxY,maxZ）。拆场按它算，不按声明尺寸猜。 */
    private final Map<UUID, int[]> arenaBuilt = new LinkedHashMap<>();
    private final Map<UUID, HomePos> arenaHomes = new LinkedHashMap<>();
    private int tickCounter;

    public static TrialManager get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(TrialManager::load, TrialManager::new, DATA_NAME);
    }

    public static TrialManager load(CompoundTag tag)
    {
        TrialManager manager = new TrialManager();
        if (tag.contains(TAG_CELLS))
        {
            CompoundTag cells = tag.getCompound(TAG_CELLS);
            for (String key : cells.getAllKeys())
            {
                try
                {
                    manager.arenaCells.put(UUID.fromString(key), cells.getInt(key));
                }
                catch (IllegalArgumentException ignored)
                {
                    // 坏 UUID 直接丢，别让整份存档读不出来
                }
            }
        }
        if (tag.contains(TAG_REACH))
        {
            CompoundTag reach = tag.getCompound(TAG_REACH);
            for (String key : reach.getAllKeys())
            {
                try
                {
                    manager.arenaReach.put(UUID.fromString(key), reach.getInt(key));
                }
                catch (IllegalArgumentException ignored)
                {
                    // 同上
                }
            }
        }
        if (tag.contains(TAG_BUILT))
        {
            CompoundTag built = tag.getCompound(TAG_BUILT);
            for (String key : built.getAllKeys())
            {
                try
                {
                    int[] span = built.getIntArray(key);
                    if (span.length == 6)
                    {
                        manager.arenaBuilt.put(UUID.fromString(key), span);
                    }
                }
                catch (IllegalArgumentException ignored)
                {
                    // 同上
                }
            }
        }
        if (tag.contains(TAG_HOMES))
        {
            CompoundTag homes = tag.getCompound(TAG_HOMES);
            for (String key : homes.getAllKeys())
            {
                try
                {
                    HomePos home = HomePos.fromTag(homes.getCompound(key));
                    if (home != null)
                    {
                        manager.arenaHomes.put(UUID.fromString(key), home);
                    }
                }
                catch (IllegalArgumentException ignored)
                {
                    // 同上
                }
            }
        }
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

        CompoundTag cells = new CompoundTag();
        this.arenaCells.forEach((uuid, cell) -> cells.putInt(uuid.toString(), cell));
        tag.put(TAG_CELLS, cells);

        CompoundTag reach = new CompoundTag();
        this.arenaReach.forEach((uuid, depth) -> reach.putInt(uuid.toString(), depth));
        tag.put(TAG_REACH, reach);

        CompoundTag built = new CompoundTag();
        this.arenaBuilt.forEach((uuid, span) -> built.putIntArray(uuid.toString(), span));
        tag.put(TAG_BUILT, built);

        CompoundTag homes = new CompoundTag();
        this.arenaHomes.forEach((uuid, home) -> homes.put(uuid.toString(), home.toTag()));
        tag.put(TAG_HOMES, homes);
        return tag;
    }

    // ------------------------------------------------------------- 场地记账

    /**
     * 给这个玩家在这段玩法里挑一个空闲隔间编号（取最小空闲号，重启后也能接上账）。
     * 编号按玩法分段，所以闯关与爬塔永远不会共用同一块地。
     *
     * @param arena 换段时用来拆掉旧那间；null = 只做记账（自检里用）
     */
    public int allocateCell(UUID playerId, TrialMode mode, @Nullable ServerLevel arena)
    {
        Integer existing = this.arenaCells.get(playerId);
        if (existing != null && existing >= mode.cellBase() && existing < mode.cellBase() + 1000)
        {
            return existing;
        }
        if (existing != null && arena != null && sessionOf(playerId) == null)
        {
            // 换玩法了。旧编号要是只被覆盖掉，就再没人记得那儿有一间房，平台会永远留在虚空里。
            // 有活会话时一律不拆：那间就是他此刻站着的场地（开局入口已先拒，这里是第二道保险）。
            com.trialportal.arena.ArenaService.releaseCellAt(arena, existing, reachOf(playerId));
        }
        int candidate = mode.cellBase();
        while (this.arenaCells.containsValue(candidate))
        {
            candidate++;
        }
        this.arenaCells.put(playerId, candidate);
        this.setDirty();
        return candidate;
    }

    public int cellOf(UUID playerId)
    {
        return this.arenaCells.getOrDefault(playerId, ArenaService.LOBBY_CELL);
    }

    public void freeCell(UUID playerId)
    {
        this.arenaReach.remove(playerId);
        this.arenaBuilt.remove(playerId);
        if (this.arenaCells.remove(playerId) != null)
        {
            this.setDirty();
        }
    }

    /**
     * 记下本场往 +Z 建出去多远、以及真实写过的区域。两个都必须进存档：拆场盒子按它们算，
     * 只按房间表声明的 {@code size} 反推的话，任何超出声明尺寸的装饰都会留在虚空里，
     * 连带它所在的那几个强制加载区块也还不回去。
     */
    public void recordBuilt(UUID playerId, int reach, @Nullable AABB region)
    {
        Integer old = this.arenaReach.get(playerId);
        if (old == null || old < reach)
        {
            this.arenaReach.put(playerId, reach);
            this.setDirty();
        }
        if (region == null)
        {
            return;
        }
        int[] span = this.arenaBuilt.get(playerId);
        if (span == null)
        {
            this.arenaBuilt.put(playerId, spanOf(region));
            this.setDirty();
            return;
        }
        if (span[0] > region.minX) span[0] = (int) Math.floor(region.minX);
        if (span[1] > region.minY) span[1] = (int) Math.floor(region.minY);
        if (span[2] > region.minZ) span[2] = (int) Math.floor(region.minZ);
        if (span[3] < region.maxX) span[3] = (int) Math.ceil(region.maxX);
        if (span[4] < region.maxY) span[4] = (int) Math.ceil(region.maxY);
        if (span[5] < region.maxZ) span[5] = (int) Math.ceil(region.maxZ);
        this.setDirty();
    }

    public int reachOf(UUID playerId)
    {
        return this.arenaReach.getOrDefault(playerId, 0);
    }

    /** 本场真实写过的区域；没记过就是 null（调用点回退到按声明尺寸算的盒子）。 */
    @Nullable
    public AABB builtBoxOf(UUID playerId)
    {
        int[] span = this.arenaBuilt.get(playerId);
        return span == null || span.length < 6 ? null
                : new AABB(span[0], span[1], span[2], span[3], span[4], span[5]);
    }

    private static int[] spanOf(AABB region)
    {
        return new int[] {(int) Math.floor(region.minX), (int) Math.floor(region.minY),
                (int) Math.floor(region.minZ), (int) Math.ceil(region.maxX), (int) Math.ceil(region.maxY),
                (int) Math.ceil(region.maxZ)};
    }

    public Map<UUID, Integer> cellsSnapshot()
    {
        return new LinkedHashMap<>(this.arenaCells);
    }

    /**
     * 记下"进场地之前人在哪"。每次都覆盖：以前只在没记录时写，于是"进大厅 → 回家 → 换个门再进来"之后
     * 回返点还停在第一次的位置。场地内的坐标一律不记——回返点落在场地上等于把人送回虚空。
     */
    public void recordHome(UUID playerId, HomePos home)
    {
        if (com.trialportal.arena.ArenaService.isArena(home.dimension()))
        {
            return;
        }
        this.arenaHomes.put(playerId, home);
        this.setDirty();
    }

    @Nullable
    public HomePos takeHome(UUID playerId)
    {
        HomePos home = this.arenaHomes.remove(playerId);
        if (home != null)
        {
            this.setDirty();
        }
        return home;
    }

    /** 进维度前站在哪儿、要送回哪儿。 */
    public record HomePos(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch)
    {
        CompoundTag toTag()
        {
            CompoundTag tag = new CompoundTag();
            tag.putString("dim", this.dimension.location().toString());
            tag.putDouble("x", this.x);
            tag.putDouble("y", this.y);
            tag.putDouble("z", this.z);
            tag.putFloat("yaw", this.yaw);
            tag.putFloat("pitch", this.pitch);
            return tag;
        }

        @Nullable
        static HomePos fromTag(CompoundTag tag)
        {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
            if (dim == null)
            {
                return null;
            }
            return new HomePos(ResourceKey.create(Registries.DIMENSION, dim),
                    tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getFloat("yaw"), tag.getFloat("pitch"));
        }
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
    /**
     * @param dimension 会话真正所在的维度——**不能**取玩家此刻的维度：野外直连开局时人还在主世界，
     *                  取错了会让下一 tick 直接判"离开范围"（真机撞到的）。
     */
    public boolean start(ServerPlayer player, TrialDefinition definition, BlockPos origin, int cell,
                         ResourceKey<Level> dimension)
    {
        if (this.sessions.containsKey(player.getUUID()))
        {
            TrialFeedback.failure(player, "你已经有一场试炼在进行中");
            return false;
        }
        TrialSession session = new TrialSession(player.getUUID(), player.getScoreboardName(), definition.id(),
                dimension, origin);
        session.cell = cell;
        session.mode = definition.mode; // 开局就钉住玩法，之后不再靠反查定义（见 TrialSession#mode）
        session.seed = player.serverLevel().getRandom().nextLong(); // 这一局的布局从此可复现

        if (definition.isEndless())
        {
            int offset = TrialConfig.TOWER_RESUME_OFFSET.get();
            int floor = Math.max(1, recordedFloor(player.getUUID()) + 1 + offset);
            session.waveIndex = floor - 1;
        }

        if (definition.mode == TrialMode.TRIAL)
        {
            int normalRooms = Math.max(1, definition.finiteWaveCount() - 1);
            session.roomPlan = RoomLibrary.plan(normalRooms, RandomSource.create(session.seed));
            if (session.roomPlan == null)
            {
                TrialFeedback.failure(player, "房间池不够：需要至少 1 间普通房与 1 间 BOSS 房（data/"
                        + "trial_portal/arena_rooms/）");
                return false;
            }
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
            LOGGER.warn("[trial_portal] 开局失败：{} 的第 {} 波取不到内容（定义或 scaling 有问题）",
                    definition.id(), session.waveNumber());
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
            // 副本会话的定义本来就不在 trials/ 里（它的 definitionId 是 challenges/ 的 id），
            // 那条路没有"下一波"要点，不能报成"试炼已被移除"。
            TrialFeedback.failure(player, session.challengeId != null
                    ? "副本不用继续下一波：时限到自动结算，随时点返回终端离场"
                    : "这份试炼已从数据包里移除，无法继续");
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
        // 玩法读会话自己钉的那份：副本会话的 definitionId 不在 trials/ 里，以前在这里查不到就直接 return，
        // 结果人在副本里死掉时拆台、清怪、转存奖励整段被跳过。
        boolean clearsPending = switch (session.mode)
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
        if (definition != null && definition.isEndless())
        {
            onTowerDeath(player, session);
        }
        if (TrialConfig.GENERAL_CLEAR_MOBS_ON_END.get())
        {
            clearMobs(player.server.getLevel(session.dimension), session);
        }
        TrialFeedback.announce(player, "试炼失败",
                clearsPending ? "累计奖励已清空" : "累计奖励留到下一场",
                "[试炼] 你死了 · " + sessionLabel(session, definition));
        // 死亡由原版重生流程送玩家回去，这里只拆隔间、清 cell 记录（进深从记账里读）
        com.trialportal.arena.ArenaService.releaseCell(
                com.trialportal.arena.ArenaService.level(player.server), this, player.getUUID(), session.cell);
    }

    /** 播报用的名字：trials/ 里查得到就用它；副本会话的定义在 challenges/，别拿副本 id 去 trials/ 里找。 */
    private static String sessionLabel(TrialSession session, @Nullable TrialDefinition definition)
    {
        if (definition != null)
        {
            return definition.displayName;
        }
        ChallengeDefinition challenge = session.challengeId == null ? null : ChallengeLibrary.get(session.challengeId);
        return challenge != null ? challenge.displayNameOrDefault() : session.definitionId;
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
            com.trialportal.arena.ArenaService.releaseCell(com.trialportal.arena.ArenaService.level(server),
                    this, session.playerId, session.cell);
            return;
        }
        TrialDefinition definition = TrialLibrary.get(session.definitionId);
        if (session.challengeId != null)
        {
            stepDungeon(server, player, session, interval);
            return;
        }
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
        if (!stillInsideOrEnded(player, session, definition, interval))
        {
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

    /**
     * 越界处理：宽限期内给倒计时提示并跳过本轮推进，超时则统一结算离场。
     *
     * @return true 表示本场还在、本轮可以继续推进
     */
    private boolean stillInsideOrEnded(ServerPlayer player, TrialSession session,
                                       @Nullable TrialDefinition definition, int interval)
    {
        if (isInsideArena(player, session, definition))
        {
            session.outsideTicks = 0;
            return true;
        }
        session.outsideTicks += interval;
        int grace = TrialConfig.GENERAL_ABANDON_GRACE_TICKS.get();
        if (session.outsideTicks < grace)
        {
            // 先警告再离场：跑远捡个东西不该直接输掉一整场
            TrialFeedback.actionbar(player, "已离开试炼范围，" + ((grace - session.outsideTicks) / 20 + 1)
                    + " 秒内回来，否则本场结束（已攒奖励照发）");
            return false;
        }
        end(player, session, definition, Outcome.LEFT);
        TrialFeedback.announce(player, "试炼结束", "已离开试炼范围", "[试炼] 你离开了范围，已攒的奖励已发放");
        return false;
    }

    /**
     * 开一局资源副本：不计波次，时限内怪持续补到上限，杀一只攒一份奖，出去统一结算。
     * 隔间分配、离场、拆台这些与波次玩法完全共用，只有玩法循环不同。
     */
    public boolean startDungeon(ServerPlayer player, ChallengeDefinition challenge, String difficultyKey,
                                BlockPos origin, int cell, ResourceKey<Level> dimension)
    {
        if (this.sessions.containsKey(player.getUUID()))
        {
            TrialFeedback.failure(player, "你已经有一场试炼在进行中");
            return false;
        }
        ChallengeDefinition.Difficulty difficulty = challenge.difficulty(difficultyKey);
        if (difficulty == null)
        {
            TrialFeedback.failure(player, "这份副本没有「" + difficultyKey + "」难度");
            return false;
        }
        TrialSession session = new TrialSession(player.getUUID(), player.getScoreboardName(), challenge.id(),
                dimension, origin);
        session.cell = cell;
        session.mode = TrialMode.CHALLENGE;
        session.seed = player.serverLevel().getRandom().nextLong();
        session.challengeId = challenge.id();
        session.difficulty = difficultyKey;
        session.remainingTicks = challenge.durationTicks;
        this.sessions.put(player.getUUID(), session);
        List<TrialDefinition.RewardEntry> carried = this.carriedOver.remove(player.getUUID());
        if (carried != null && !carried.isEmpty())
        {
            session.addReward(carried);
            TrialFeedback.chat(player, "[试炼] 上一场攒下的奖励已并入本场待结算池");
        }
        ServerLevel level = player.server.getLevel(dimension);
        if (level != null)
        {
            spawnDungeonBatch(level, player, session, difficulty);
        }
        TrialFeedback.title(player, challenge.displayNameOrDefault(),
                difficulty.displayNameOrDefault(difficultyKey) + " · 时限 " + challenge.durationTicks / 20 + " 秒");
        TrialFeedback.chat(player, "[副本] 杀一只攒一份奖，出去（点返回终端）就结算");
        return true;
    }

    /** 副本的一轮：时限、击杀计奖、按节奏补怪。 */
    private void stepDungeon(MinecraftServer server, ServerPlayer player, TrialSession session, int interval)
    {
        ChallengeDefinition challenge = ChallengeLibrary.get(session.challengeId);
        ChallengeDefinition.Difficulty difficulty = challenge == null ? null : challenge.difficulty(session.difficulty);
        if (challenge == null || difficulty == null)
        {
            end(player, session, null, Outcome.ABANDONED);
            TrialFeedback.failure(player, "这份副本定义已被移除，本场中止");
            return;
        }
        ServerLevel level = server.getLevel(session.dimension);
        if (level == null || !level.isLoaded(session.origin))
        {
            return; // 原点区块没加载，跳过本轮：否则会把「没加载」误判成「怪都死了」
        }
        if (!stillInsideOrEnded(player, session, null, interval))
        {
            return;
        }
        int died = pruneDeadMobs(level, session);
        if (died > 0)
        {
            session.kills += died;
            for (int i = 0; i < died; i++)
            {
                session.addReward(difficulty.killRewards);
            }
        }
        session.remainingTicks -= interval;
        if (session.remainingTicks <= 0)
        {
            TrialFeedback.chat(player, "[副本] 时限到，" + session.kills + " 只的奖励正在结算");
            end(player, session, null, Outcome.SETTLED);
            return;
        }
        session.respawnCooldown -= interval;
        if (session.respawnCooldown > 0)
        {
            return;
        }
        session.respawnCooldown = difficulty.spawnIntervalTicks;
        if (session.aliveMobs.size() < difficulty.mobCap)
        {
            spawnDungeonBatch(level, player, session, difficulty);
        }
    }

    /** 补一批怪，不超过该档的场上限。 */
    private void spawnDungeonBatch(ServerLevel level, ServerPlayer owner, TrialSession session,
                                   ChallengeDefinition.Difficulty difficulty)
    {
        int room = difficulty.mobCap - session.aliveMobs.size();
        int batch = Math.min(difficulty.spawnBatch, Math.max(0, room));
        for (int i = 0; i < batch; i++)
        {
            TrialDefinition.MobEntry entry = difficulty.mobs.get(session.spawnCursor++ % difficulty.mobs.size());
            Vec3 position = findSpawnPosition(level, session.origin, difficulty.spawnRadiusOrDefault(),
                    difficulty.spawnHeightOrDefault());
            Mob mob = createMobAt(level, entry, position);
            if (mob == null)
            {
                continue;
            }
            MobStrengths.apply(mob, MobStrength.combined(difficulty.strength, entry.strength));
            if (mob instanceof Enemy)
            {
                mob.setTarget(owner);
            }
            mob.setPersistenceRequired();
            if (level.addFreshEntity(mob))
            {
                session.aliveMobs.add(mob.getUUID());
            }
        }
    }

    private boolean beginWave(ServerPlayer player, TrialSession session, TrialDefinition definition)
    {
        TrialDefinition.WaveContent content = definition.waveFor(session.waveIndex);
        if (content == null)
        {
            LOGGER.warn("[trial_portal] {} 取不到第 {} 波内容", definition.id(), session.waveNumber());
            return false;
        }
        ServerLevel level = player.server.getLevel(session.dimension);
        if (level == null)
        {
            LOGGER.warn("[trial_portal] 会话所在维度 {} 没加载", session.dimension.location());
            return false;
        }
        session.aliveMobs.clear();
        session.bossAffixes.clear();
        session.phase = TrialSession.Phase.IN_WAVE;
        if (session.roomPlan != null)
        {
            buildNextRoom(level, player, session);
        }

        // 上限优先看这份定义自己写的（JSON 里就能调），没写才用 general 的全局默认
        int budget = Math.min(definition.spawnBudgetOrDefault(), definition.maxAliveOrDefault());
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

    /**
     * 按房间序列把下一间房建出来，并把站怪点交给会话。房间沿 +Z 排开，中间用走廊接上，
     * 所以"打完一波往里走一间"是玩家能感知的空间推进，而不是原地换一批怪。
     */
    private void buildNextRoom(ServerLevel level, ServerPlayer player, TrialSession session)
    {
        String roomId = session.roomPlan.get(Math.min(session.roomIndex, session.roomPlan.size() - 1));
        RoomDefinition room = RoomLibrary.get(roomId);
        if (room == null)
        {
            LOGGER.warn("[trial_portal] 房间 {} 不存在，本波退回随机扫位", roomId);
            session.spawnPoints = null;
            return;
        }
        session.roomIndex++;
        int cellRadius = ArenaService.radius();
        int cursor = session.reach > 0 ? session.reach : cellRadius + 2;
        BlockPos origin = new BlockPos(session.origin.getX() - room.width() / 2, ArenaService.FLOOR_Y,
                session.origin.getZ() + cursor);
        if (session.reach > 0)
        {
            AABB corridor = RoomBuilder.buildCorridor(level, session.origin.getX(),
                    session.origin.getZ() + session.reach, origin.getZ() - 1,
                    RoomBuilder.entryFloor(), RoomBuilder.entryWall(),
                    RoomBuilder.defaultLight(), RoomBuilder.defaultLightSpacing());
            session.region = session.region == null ? corridor : join(session.region, corridor);
        }
        RoomBuilder.Built built = RoomBuilder.build(level, room, origin,
                RandomSource.create(session.seed + session.waveIndex * 31L)); // 每波布局不同，但同一场可复现
        session.spawnPoints = built.spawnPoints();
        session.bossAnchor = room.bossRoom ? room.bossAnchor(origin) : null;
        session.region = session.region == null ? built.region() : join(session.region, built.region());
        session.reach = origin.getZ() + room.depth() - session.origin.getZ();
        recordBuilt(player.getUUID(), session.reach, session.region);
        LOGGER.info("[trial_portal] 第 {} 波建房 {} @ {} 室内 {}x{}x{} 站怪点 {} 个 进深 {}",
                session.waveNumber(), room.id(), origin.toShortString(),
                room.width(), room.height(), room.depth(), built.spawnPoints().size(), session.reach);
        TrialFeedback.chat(player, "往里进一间：" + room.displayNameOrDefault());
    }

    private static net.minecraft.world.phys.AABB join(net.minecraft.world.phys.AABB a, net.minecraft.world.phys.AABB b)
    {
        return new net.minecraft.world.phys.AABB(
                Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    private boolean spawnOne(ServerLevel level, ServerPlayer owner, TrialSession session, TrialDefinition definition,
                             TrialDefinition.WaveContent content, TrialDefinition.MobEntry entry)
    {
        // BOSS 站这间 BOSS 房中心的舞台（三对柱子围出来的那块地），不跟小怪一起排在内圈门边
        Vec3 position = entry.boss && session.bossAnchor != null
                ? Vec3.atBottomCenterOf(session.bossAnchor)
                : session.spawnPoints == null || session.spawnPoints.isEmpty()
                        ? findSpawnPosition(level, session.origin, definition.spawnRadiusOrDefault(),
                                definition.spawnHeightOrDefault())
                        : Vec3.atBottomCenterOf(session.spawnPoints.get(session.spawnCursor++ % session.spawnPoints.size()));
        Mob mob = createMobAt(level, entry, position);
        if (mob == null)
        {
            return false;
        }
        // 强度链：定义级 → 波次/层段级（已经乘在 content.strength 里）→ 单只怪级
        MobStrengths.apply(mob, MobStrength.combined(content.strength, entry.strength));
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

    /** 造一只怪并摆到位：不做加成、不入世界。波次玩法与资源副本共用这一份，免得两份创建逻辑漂移。 */
    @Nullable
    private static Mob createMobAt(ServerLevel level, TrialDefinition.MobEntry entry, Vec3 position)
    {
        ResourceLocation typeId = entry.entityId();
        if (typeId == null)
        {
            LOGGER.warn("[trial_portal] entity ID 非法：{}", entry.entity);
            return null;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null)
        {
            LOGGER.warn("[trial_portal] 找不到实体类型 {}", typeId);
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob mob))
        {
            LOGGER.warn("[trial_portal] {} 不是可生成的生物", typeId);
            return null;
        }
        if (entry.nbt != null && !entry.nbt.isBlank())
        {
            applyNbt(mob, entry.nbt);
        }
        mob.setPos(position.x, position.y, position.z);
        mob.setYRot(level.getRandom().nextFloat() * 360.0F);
        return mob;
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

    /** 生成位置是「扫」出来的：16 次随机水平取点，每次从初始高度上下各扫 5 格找落脚位。 */
    private static Vec3 findSpawnPosition(ServerLevel level, BlockPos origin, double radiusValue, double heightValue)
    {
        double radius = Math.max(1.0D, radiusValue);
        double height = Math.max(1.0D, heightValue);
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

    private static int pruneDeadMobs(ServerLevel level, TrialSession session)
    {
        int died = 0;
        Iterator<java.util.UUID> iterator = session.aliveMobs.iterator();
        while (iterator.hasNext())
        {
            UUID uuid = iterator.next();
            Entity entity = level.getEntity(uuid);
            if (entity == null || (entity instanceof LivingEntity living && !living.isAlive()))
            {
                iterator.remove();
                died++;
            }
        }
        return died;
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

    private boolean isInsideArena(ServerPlayer player, TrialSession session, @Nullable TrialDefinition definition)
    {
        if (!player.level().dimension().equals(session.dimension))
        {
            return false; // 换维度 = 放弃
        }
        return withinAllowedArea(player.position(), session.origin, abandonLimit(definition), session.region);
    }

    /** 越界判定的距离上限：0 = 永不判放弃，负数（或没有定义）= 用配置默认。 */
    private double abandonLimit(@Nullable TrialDefinition definition)
    {
        double limit = definition == null ? -1.0D : definition.abandonDistanceOrDefault();
        if (limit < 0.0D)
        {
            limit = TrialConfig.GENERAL_DEFAULT_ABANDON_DISTANCE.get();
        }
        return limit;
    }

    /**
     * 允许活动的范围 = 以出发平台为圆心的距离圈 ∪ 已建房与走廊的矩形。
     * 两块都要：试炼玩法沿 +Z 穿过几间房，只按半径判会把正常推进误判成逃跑；
     * 只看已建区域又会把「站在台上右键继续下一波」的玩家判成逃跑（真机撞到的就是这个）。
     */
    static boolean withinAllowedArea(Vec3 position, BlockPos origin, double limit,
                                     @Nullable net.minecraft.world.phys.AABB region)
    {
        if (limit == 0.0D)
        {
            return true;
        }
        Vec3 center = Vec3.atCenterOf(origin);
        if (position.distanceToSqr(center) <= limit * limit)
        {
            return true;
        }
        return region != null && region.inflate(2.0D).contains(position);
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
        boolean endless = definition != null && definition.isEndless();
        if (outcome == Outcome.SETTLED && endless)
        {
            recordFloor(player.getUUID(), session.waveNumber());
        }
        if (outcome == Outcome.ABANDONED)
        {
            // 只有管理员中止与定义丢失会作废奖励；「走出去」不再吞奖励
            session.pending.clear();
        }
        else
        {
            if (outcome == Outcome.LEFT)
            {
                // 出去就算没通关：奖励照发，但不算成功，也不给通关那一份
                TrialFeedback.chat(player, "[试炼] 提前离场，本场按未通关计");
            }
            grantRewards(player, session);
        }
        clearMobs(level, session);
        com.trialportal.arena.ArenaService.returnHome(player);
    }

    /**
     * 点返回终端 = 离场并把已攒的奖励结掉。没有会话时只是回家。
     * 结算与离场再不是两条路：以前点错一下就把整场奖励丢了。
     */
    public void leave(ServerPlayer player)
    {
        TrialSession session = this.sessions.get(player.getUUID());
        if (session == null)
        {
            com.trialportal.arena.ArenaService.returnHome(player);
            return;
        }
        // 一律按「出去就结算」处理。以前这里先看 trials/ 里有没有定义、没有就当 ABANDONED，
        // 而副本会话本来就不在 trials/ 里 —— 结果杀攒的奖励被静默吞掉（真机验出来的）。
        end(player, session, TrialLibrary.get(session.definitionId), Outcome.LEFT);
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
                int size = Math.min(remaining, 64);
                ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(itemId), size);
                remaining -= size;
                // Inventory#add 会把装不下的部分留在传进去的那个 stack 上，所以必须传本尊：
                // 以前传 stack.copy()，背包只吃得下一半时返回 false 而副本仍是满叠，掉地上就凭空多出一倍。
                if (toInventory)
                {
                    player.getInventory().add(stack);
                }
                if (!stack.isEmpty())
                {
                    // 开关关掉（reward_to_inventory=false）就是全掉脚下，配置注释一直是这么写的；
                    // 以前的写法是既不进包也不掉地上，只把"发放 N 件"报出来——整批奖励凭空消失。
                    dropAtFeet(player, stack);
                }
                granted += size;
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
