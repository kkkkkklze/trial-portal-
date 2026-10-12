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
    /**
     * 拆塔 goal 的优先级。原版的乱走（{@code RandomStrollGoal}）在 8、看玩家在 2，
     * 取 3 是为了压过乱走又不抢"挨打回身"那条仇恨链——改这个数字前先看 {@link #assignDefenseFocus} 的注释。
     */
    private static final int TOWER_GOAL_PRIORITY = 3;

    public enum Outcome
    {
        /** 主动结算或打完：发奖，爬塔记层 */
        SETTLED,
        /** 走出场地（点往返门 / 越界离开）：已攒的奖励照发，但不算通关、不记塔层 */
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
        int base = mode.cellBase();
        Integer existing = this.arenaCells.get(playerId);
        if (existing != null && existing >= base && existing < base + CELLS_PER_MODE)
        {
            return existing;
        }
        if (existing != null && arena != null && sessionOf(playerId) == null)
        {
            // 换玩法了。旧编号要是只被覆盖掉，就再没人记得那儿有一间房，平台会永远留在虚空里。
            // 有活会话时一律不拆：那间就是他此刻站着的场地（开局入口已先拒，这里是第二道保险）。
            // 必须把真实足迹一起递过去：只按声明盒子拆，超出尺寸的装饰（走廊拐行、隔断）就连同它的
            // 强制加载区块一起留在虚空里。
            com.trialportal.arena.ArenaService.releaseCellAt(arena, existing, reachOf(playerId),
                    builtBoxOf(playerId));
        }
        if (existing != null)
        {
            // 进深与足迹是"那一间 cell"的属性，不是玩家的。带着上一段的数字开新间，recordBuilt 会把两者
            // 并成一个盒子：拆台时主线程要扫几十万个位置，还会把沿途别人正在用的隔间一起 AIR 掉。
            clearCellAccounting(playerId);
        }
        int candidate = base;
        while (candidate < base + CELLS_PER_MODE && this.arenaCells.containsValue(candidate))
        {
            candidate++;
        }
        if (candidate >= base + CELLS_PER_MODE)
        {
            LOGGER.error("[trial_portal] {} 段的隔间编号已用尽（{}~{}），拒绝再开一场",
                    mode, base, base + CELLS_PER_MODE - 1);
            return -1;
        }
        this.arenaCells.put(playerId, candidate);
        this.setDirty();
        return candidate;
    }

    /** 一个玩法段能分到的隔间数。三段各 1000 个编号，越界就会踩进下一个分段、两个人共占一块地。 */
    public static final int CELLS_PER_MODE = 1000;

    /** 只销进深与足迹，不销编号（编号由 freeCell 管）。换间之后这两个值就属于另一间房了。 */
    private void clearCellAccounting(UUID playerId)
    {
        // 两次 remove 都必须真的执行：写成 `a != null || b != null` 会在前者命中时短路，
        // 足迹就悄悄留在账上（这条判据第一次跑就把它抓出来了）。
        boolean touched = this.arenaReach.remove(playerId) != null;
        touched |= this.arenaBuilt.remove(playerId) != null;
        if (touched)
        {
            this.setDirty();
        }
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

    /** 回返点还在不在。开服清扫那条路必须"还在"——人被接回来时靠它才不回世界出生点。 */
    public boolean hasHome(UUID playerId)
    {
        return this.arenaHomes.containsKey(playerId);
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
            // 那条路没有"下一波"要点，不能报成"试炼已被移除"。守塔的在 defenses/，同理。
            TrialFeedback.failure(player, session.challengeId != null
                    ? "副本不用继续下一波：时限到自动结算，随时点往返门离场"
                    : session.defenseId != null
                            ? "守塔不用点传送门：波次自己往前走，塔血归零或你死亡即判负"
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
            // 守塔：死亡即判负（§9 第 11 条②），判负就没有"下一场接着用"这回事，攒的奖随之作废
            case DEFENSE -> true;
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
        if (session.mode == TrialMode.DEFENSE)
        {
            // 守塔的失败原因有两支，各说各的：塔破与人死不能被听成同一句，玩家得知道是哪儿没守住
            TrialFeedback.announce(player, "守塔失败 · 第 " + session.waveNumber() + " 波",
                    "你死了，塔没人守了（已攒奖励作废）",
                    "[守塔] 玩家死亡，本场判负");
        }
        else
        {
            TrialFeedback.announce(player, "试炼失败",
                    clearsPending ? "累计奖励已清空" : "累计奖励留到下一场",
                    "[试炼] 你死了 · " + session.displayName());
        }
        // 死亡由原版重生流程送玩家回去，这里只拆隔间、清 cell 记录（进深从记账里读）
        com.trialportal.arena.ArenaService.releaseCell(
                com.trialportal.arena.ArenaService.level(player.server), this, player.getUUID(), session.cell);
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
        if (session.defenseId != null)
        {
            stepDefense(server, player, session, interval);
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
    /**
     * 开一局守塔（DEFENSE 玩法）。与副本同一条隔间链路，只是内容是 {@code defenses/}，
     * 并且场地按 {@code ward_radius} 加宽、台心立一座塔。
     */
    public boolean startDefense(ServerPlayer player, DefenseDefinition defense, BlockPos origin, int cell,
                                ResourceKey<Level> dimension, BlockPos towerPos)
    {
        if (this.sessions.containsKey(player.getUUID()))
        {
            TrialFeedback.failure(player, "你已经有一场试炼在进行中，先用往返门结算");
            return false;
        }
        TrialSession session = new TrialSession(player.getUUID(), player.getScoreboardName(), defense.id(),
                dimension, origin);
        session.cell = cell;
        session.mode = TrialMode.DEFENSE;
        session.defenseId = defense.id();
        session.towerPos = towerPos;
        // 这一场用的是副本：/tei defense 改的必须是它，而不是数据包里那份公共的
        session.liveDefense = defense.copy();
        session.seed = player.serverLevel().getRandom().nextLong();
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null || !beginDefenseWave(level, player, session, session.liveDefense))
        {
            LOGGER.warn("[trial_portal] 守塔 {} 的第 1 波一只怪都没刷出来，不开这一局", defense.id());
            return false;
        }
        this.sessions.put(player.getUUID(), session);
        this.setDirty();
        TrialFeedback.title(player, "守塔 · " + defense.displayNameOrDefault(),
                "共 " + defense.finiteWaveCount() + " 波：塔血归零或你死亡都算输");
        return true;
    }

    /**
     * 守塔一轮的裁决——只读状态、不碰世界，所以四条分支能在无头门里逐一走到。
     * <p>
     * 顺序里有两处会被人改错的地方，都钉住了：塔破要排在「本波清空」之前（最后一只怪死在塔归零之后
     * 的那同一 tick，这场算输不算赢）；场上还有怪时不许进间歇（否则波次会叠着刷）。
     */
    enum DefenseStep
    {
        /** 本波还在打 */
        FIGHTING,
        /** 间歇倒计时中 */
        COUNTDOWN,
        /** 本波清空，还有下一波 */
        WAVE_CLEARED,
        /** 最后一波也清空了：守住 */
        WON,
        /** 塔血归零 */
        LOST_TOWER
    }

    static DefenseStep nextDefenseStep(TrialSession session, DefenseDefinition defense, boolean towerDestroyed)
    {
        if (towerDestroyed)
        {
            return DefenseStep.LOST_TOWER;
        }
        if (!session.aliveMobs.isEmpty())
        {
            return DefenseStep.FIGHTING;
        }
        if (session.phase == TrialSession.Phase.INTERMISSION)
        {
            return DefenseStep.COUNTDOWN;
        }
        return defense.waveFor(session.waveIndex + 1) == null ? DefenseStep.WON : DefenseStep.WAVE_CLEARED;
    }

    /**
     * 守塔的波次自己往前走，塔血归零即判负（§9 第 11 条①）。
     * 范围外判定与副本共用一条（{@code stillInsideOrEnded}）；玩家死亡那条在 {@code onPlayerDeath} 里报因。
     */
    private void stepDefense(MinecraftServer server, ServerPlayer player, TrialSession session, int interval)
    {
        DefenseDefinition loaded = DefenseLibrary.get(session.defenseId);
        if (loaded == null)
        {
            end(player, session, null, Outcome.ABANDONED);
            TrialFeedback.failure(player, "这份守塔定义已被移除，本场中止");
            return;
        }
        // 参数看这一场自己的那份（可能被 /tei defense 改过），存在性看库里的原件：
        // 分开是因为"数据包被 reload 掉了"必须中止，而"这一场调过波次"不该被当成中止。
        DefenseDefinition defense = session.liveDefense == null ? loaded : session.liveDefense;
        ServerLevel level = server.getLevel(session.dimension);
        if (level == null || session.towerPos == null || !level.isLoaded(session.towerPos))
        {
            return; // 塔所在区块没加载，别把"读不到"当成"塔没了"
        }
        if (!stillInsideOrEnded(player, session, null, interval))
        {
            return;
        }
        pruneDeadMobs(level, session);
        String status = "守塔 第 " + session.waveNumber() + " 波 · 塔血 " + towerStatusAt(level, session.towerPos)
                + " · 场上 " + session.aliveMobs.size() + " 只";
        switch (nextDefenseStep(session, defense, isTowerDown(level, session.towerPos)))
        {
            case FIGHTING -> TrialFeedback.actionbar(player, status);
            case LOST_TOWER ->
            {
                end(player, session, null, Outcome.ABANDONED);
                TrialFeedback.announce(player, "守塔失败 · " + defense.displayNameOrDefault(),
                        "第 " + session.waveNumber() + " 波，塔血归零",
                        "[守塔] 塔被摧毁，本场判负（已攒奖励作废）");
            }
            case COUNTDOWN ->
            {
                session.intermissionTicksLeft -= interval;
                if (session.intermissionTicksLeft <= 0)
                {
                    session.waveIndex++;
                    if (!beginDefenseWave(level, player, session, defense))
                    {
                        end(player, session, null, Outcome.ABANDONED);
                        TrialFeedback.failure(player, "第 " + session.waveNumber() + " 波取不到内容，本场中止");
                    }
                }
                else
                {
                    TrialFeedback.actionbar(player, status + " · 下一波 "
                            + (session.intermissionTicksLeft / 20 + 1) + " 秒后压进来");
                }
            }
            case WAVE_CLEARED ->
            {
                addWaveRewards(session, defense.waveFor(session.waveIndex));
                session.phase = TrialSession.Phase.INTERMISSION;
                session.intermissionTicksLeft = defense.intermissionTicksFor(session.waveIndex);
                TrialFeedback.announce(player, "第 " + session.waveNumber() + " 波守住",
                        "歇 " + (session.intermissionTicksLeft / 20 + 1) + " 秒，下一波从结界外缘进来",
                        "[守塔] 第 " + session.waveNumber() + " 波清空，本波奖励已攒入待结算");
            }
            case WON ->
            {
                addWaveRewards(session, defense.waveFor(session.waveIndex));
                session.addReward(defense.rewards);
                // 塔血要在拆台之前读：end() 会把人送走并拆掉隔间，之后再拿方块实体就只剩"读不到"
                String remaining = towerStatusAt(level, session.towerPos);
                end(player, session, null, Outcome.SETTLED);
                TrialFeedback.announce(player, "守塔成功 · " + defense.displayNameOrDefault(),
                        defense.finiteWaveCount() + " 波全守住，塔血 " + remaining,
                        "[守塔] 守住了，奖励已发放");
            }
        }
    }

    private static void addWaveRewards(TrialSession session, @Nullable TrialDefinition.WaveContent content)
    {
        if (content != null)
        {
            session.addReward(content.rewards);
        }
    }

    /** 塔是不是已经归零。拿不到方块实体时算「没归零」：读不到不等于被打掉。 */
    private static boolean isTowerDown(ServerLevel level, BlockPos towerPos)
    {
        return level.getBlockEntity(towerPos) instanceof com.trialportal.block.entity.WardPylonBlockEntity pylon
                && pylon.destroyed();
    }

    /** 给玩家看的塔血读数：{@code 剩/总}。拿不到方块实体就说人话，不编一个数。 */
    private static String towerStatusAt(ServerLevel level, BlockPos towerPos)
    {
        return level.getBlockEntity(towerPos) instanceof com.trialportal.block.entity.WardPylonBlockEntity pylon
                ? String.format(Locale.ROOT, "%.0f/%.0f", pylon.towerHealth(), pylon.towerMax()) : "读不到";
    }

    /**
     * 起第 {@code session.waveIndex} 波：怪铺在结界外缘那一圈，不再挤在台心。
     * 一只都没刷出来时返回 false——这一局不该继续，否则下一 tick 就会「场上没怪」直接判通关。
     */
    private boolean beginDefenseWave(ServerLevel level, ServerPlayer owner, TrialSession session,
                                     DefenseDefinition defense)
    {
        TrialDefinition.WaveContent content = defense.waveFor(session.waveIndex);
        if (content == null)
        {
            LOGGER.warn("[trial_portal] 守塔 {} 取不到第 {} 波内容", defense.id(), session.waveNumber());
            return false;
        }
        session.aliveMobs.clear();
        session.bossAffixes.clear();
        session.phase = TrialSession.Phase.IN_WAVE;
        int spawned = 0;
        for (TrialDefinition.MobEntry entry : content.mobs)
        {
            int wanted = Math.max(1, entry.count);
            for (int i = 0; i < wanted; i++)
            {
                Mob mob = createMobAt(level, entry,
                        wardEdgePosition(level, session.origin, defense.wardRadius, level.getRandom()));
                if (mob == null)
                {
                    continue;
                }
                MobStrengths.apply(mob, MobStrength.combined(content.strength, entry.strength));
                if (entry.boss)
                {
                    // 头目固定打人：让它去啃塔就把这场最难的部分变没了
                    prepareBoss(mob, defense.displayNameOrDefault() + " · 头目", owner);
                    applyBossAffixes(level, mob, content.affixPool, TrialConfig.TRIAL_BOSS_AFFIX_COUNT.get(),
                            owner, session);
                }
                else
                {
                    assignDefenseFocus(mob, defense, session, owner, level.getRandom());
                }
                mob.setPersistenceRequired();
                if (level.addFreshEntity(mob))
                {
                    session.aliveMobs.add(mob.getUUID());
                    spawned++;
                    // 恶意联动排在入世界之后：它那侧的等级是怪第一个 tick 才初始化好的，这里只登记目标值
                    MaliceLink.onSpawn(mob, MaliceLink.stageIndex(TrialMode.DEFENSE, session.waveIndex, null),
                            entry.maliceLevel);
                }
            }
        }
        if (spawned == 0)
        {
            return false;
        }
        TrialFeedback.title(owner, content.title.replace("%s", owner.getScoreboardName()), null);
        return true;
    }

    /**
     * 给一只守塔怪挑目标：{@code tower_focus_share} 那部分去啃塔，其余锁玩家——这就是"怪有两个目标"的落点。
     * <p>
     * 啃塔的那只开局**不**锁玩家（否则它先跑向人，塔血一动不动）；玩家打它之后由原版
     * {@code HurtByTargetGoal} 回身，这正好是想要的"两个目标会切换"。
     * 拿不到塔位（异常场次）就退回打人，不留一只既没目标也没路径的怪。
     */
    static DefenseFocus assignDefenseFocus(Mob mob, DefenseDefinition defense, TrialSession session,
                                           @Nullable ServerPlayer owner, RandomSource random)
    {
        if (DefenseFocus.forRoll(random.nextDouble(), defense.towerFocusShare) == DefenseFocus.TOWER
                && session.towerPos != null)
        {
            mob.goalSelector.addGoal(TOWER_GOAL_PRIORITY, new com.trialportal.arena.TowerAssaultGoal(mob,
                    session.towerPos, defense.towerDamagePerHit, defense.towerHitIntervalTicks,
                    defense.wardRadius + 4.0D));
            return DefenseFocus.TOWER;
        }
        if (mob instanceof Enemy && owner != null)
        {
            mob.setTarget(owner);
        }
        return DefenseFocus.PLAYER;
    }

    /**
     * 结界外缘的落点：在半径 0.55~0.9 倍的环带里取角度，再往下扫找落脚点。
     * 不取整圆是因为围栏就压在 {@code ward_radius} 那一圈上——落点越过围栏等于把怪丢到场外，
     * 场外没有强制加载的区块，怪会卡在未加载区里让这一波永远清不完。
     */
    static Vec3 wardEdgePosition(ServerLevel level, BlockPos origin, double radiusValue, RandomSource random)
    {
        double ring = Math.max(4.0D, radiusValue);
        for (int attempt = 0; attempt < 16; attempt++)
        {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            Vec3 point = scanRingColumn(level, origin, ring, ring * (0.55D + 0.35D * random.nextDouble()), angle);
            if (point != null)
            {
                return point;
            }
        }
        for (int step = 0; step < 8; step++)
        {
            Vec3 point = scanRingColumn(level, origin, ring, ring * 0.7D, step * Math.PI / 4.0D);
            if (point != null)
            {
                return point;
            }
        }
        LOGGER.warn("[trial_portal] 结界环带（半径 {}）扫不到落脚点，这一只改站在台心东侧", ring);
        return Vec3.atBottomCenterOf(origin.offset(2, 1, 0));
    }

    /** 给定角度与距离，在 3~-1 的竖直窗口里找一格「脚下实心、头上两格空」。找不到返回 null。 */
    @Nullable
    private static Vec3 scanRingColumn(ServerLevel level, BlockPos origin, double ring, double distance, double angle)
    {
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
        for (int dy = 3; dy >= -1; dy--)
        {
            BlockPos candidate = new BlockPos(x, origin.getY() + dy, z);
            if (level.getBlockState(candidate.below()).isRedstoneConductor(level, candidate.below())
                    && level.isEmptyBlock(candidate) && level.isEmptyBlock(candidate.above()))
            {
                return Vec3.atBottomCenterOf(candidate);
            }
        }
        return null;
    }


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
        TrialFeedback.chat(player, "[副本] 杀一只攒一份奖，出去（点往返门）就结算");
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
        // 按 mobs[].count 展开出来的配比队列轮转：写僵尸×2 + 骷髅×1 就会真的按 2:1 出怪
        java.util.List<TrialDefinition.MobEntry> plan = difficulty.spawnPlan();
        for (int i = 0; i < batch; i++)
        {
            TrialDefinition.MobEntry entry = plan.get(session.spawnCursor++ % plan.size());
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
                MaliceLink.onSpawn(mob,
                        MaliceLink.stageIndex(TrialMode.CHALLENGE, session.waveIndex, session.difficulty),
                        entry.maliceLevel);
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
        long layoutSeed = session.seed + session.waveIndex * 31L; // 每波布局不同，但同一场可复现
        int cellRadius = ArenaService.radius();
        int cursor = session.reach > 0 ? session.reach : cellRadius + 2;
        // 第一间房永远正对着大厅：台沿那侧的围栏缺口是按隔间中轴线留的，横移会把玩家一脚踏进虚空，
        // 而且这一段本来就没有走廊可拐。落点多样性从第二间开始起作用。
        RoomDefinition.Layout layout = session.reach > 0 ? room.layoutFor(layoutSeed) : RoomDefinition.Layout.straight();
        int limit = ArenaService.lateralLimit(room.width());
        int lateral = Math.max(-limit, Math.min(limit, layout.lateral));
        if (lateral != layout.lateral)
        {
            // 夹过就要说：管理员把 platform_radius 调小之后，作者配的 lateral 会静默缩水，
            // 地图悄悄退回老样子，而 JSON 里还写着 6 —— 那是没人看得见的改动态。
            LOGGER.warn("[trial_portal] 房间 {} 的 layouts lateral={} 超出隔间横向余量，按 {} 建（platform_radius 现在 {}）",
                    room.id(), layout.lateral, lateral, TrialConfig.ARENA_PLATFORM_RADIUS.get());
        }
        BlockPos origin = new BlockPos(session.origin.getX() - room.width() / 2 + lateral, ArenaService.FLOOR_Y,
                session.origin.getZ() + cursor + layout.gap);
        if (session.reach > 0)
        {
            AABB corridor = RoomBuilder.buildLink(level, session.linkX,
                    session.origin.getZ() + session.reach, session.origin.getX() + lateral, origin.getZ() - 1,
                    RoomBuilder.entryFloor(), RoomBuilder.entryWall(),
                    RoomBuilder.defaultLight(), RoomBuilder.defaultLightSpacing());
            session.region = session.region == null ? corridor : join(session.region, corridor);
        }
        session.linkX = session.origin.getX() + lateral;
        RoomBuilder.Built built = RoomBuilder.build(level, room, origin,
                RandomSource.create(layoutSeed), room.paletteFor(layoutSeed));
        session.spawnPoints = built.spawnPoints();
        session.bossAnchor = room.bossRoom ? room.bossAnchor(origin) : null;
        session.region = session.region == null ? built.region() : join(session.region, built.region());
        session.reach = origin.getZ() + room.depth() - session.origin.getZ();
        recordBuilt(player.getUUID(), session.reach, session.region);
        LOGGER.info("[trial_portal] 第 {} 波建房 {} @ {} 室内 {}x{}x{} 站怪点 {} 个 进深 {} 横移 {} 间隙 {}",
                session.waveNumber(), room.id(), origin.toShortString(),
                room.width(), room.height(), room.depth(), built.spawnPoints().size(), session.reach,
                lateral, layout.gap);
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
        // 试炼按波次、爬塔按层数走阶梯，两者都是 session.waveIndex
        MaliceLink.onSpawn(mob, MaliceLink.stageIndex(definition.mode, session.waveIndex, null), entry.maliceLevel);
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

    private void prepareBoss(Mob mob, TrialDefinition definition, ServerPlayer owner)
    {
        String name = definition.mode == TrialMode.TRIAL && definition.boss != null && definition.boss.name != null
                ? definition.boss.name.replace("%s", owner.getScoreboardName())
                : owner.getScoreboardName() + " 的对手";
        prepareBoss(mob, name, owner);
    }

    /** 守塔的头目没有 trials/ 那份 boss 配置，名字由调用点给，待遇与试炼的 BOSS 同一条。 */
    private static void prepareBoss(Mob mob, String name, ServerPlayer owner)
    {
        mob.setHealth(mob.getMaxHealth());
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
        applyBossAffixes(level, mob, pool, count, owner, session);
    }

    /** 贴词条的实际动作。试炼与守塔共用这一份：池子为空就走全量已加载词条，两边同一个口径。 */
    private void applyBossAffixes(ServerLevel level, Mob mob, @Nullable List<String> pool, int count,
                                  ServerPlayer owner, TrialSession session)
    {
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
        boolean settle = outcome != Outcome.ABANDONED;
        if (!settle)
        {
            // 只有管理员中止与定义丢失会作废奖励；「走出去」不再吞奖励
            session.pending.clear();
        }
        else if (outcome == Outcome.LEFT)
        {
            // 出去就算没通关：奖励照发，但不算成功，也不给通关那一份
            TrialFeedback.chat(player, "[试炼] 提前离场，本场按未通关计");
        }
        clearMobs(level, session);
        com.trialportal.arena.ArenaService.returnHome(player);
        if (settle)
        {
            // 发奖必须排在送走之后。returnHome 里同 tick 就拆台：地板被抽成空气、强制加载松掉，
            // 所以掉在场内的物品既没人捡、也没区块替它保存——以前报的"发放 N 件（背包满的部分掉在脚下）"
            // 里那"脚下"根本不是脚下，而是正在消失的虚空（reward_to_inventory=false 时是整批消失）。
            grantRewards(player, session);
        }
    }

    /**
     * 点往返门 = 离场并把已攒的奖励结掉。没有会话时只是回家。
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
        if (com.trialportal.arena.ArenaService.isArena(player.level().dimension()))
        {
            // 场地里掉东西等于扔掉。end() 已经把发奖排在送走之后，走到这里说明顺序又被改回去了——
            // 不静默丢，转存进"下一场补发"那本账（与死亡转存同一个去处），并且把话说响。
            LOGGER.error("[trial_portal] {} 的奖励仍在场地里就要掉落，改成转存下一场：{} x {}",
                    player.getScoreboardName(), stack.getCount(), ForgeRegistries.ITEMS.getKey(stack.getItem()));
            this.carriedOver.computeIfAbsent(player.getUUID(), id -> new ArrayList<>())
                    .add(entryOf(stack));
            return;
        }
        ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), stack);
        entity.setPickUpDelay(20);
        player.level().addFreshEntity(entity);
    }

    private static TrialDefinition.RewardEntry entryOf(ItemStack stack)
    {
        TrialDefinition.RewardEntry entry = new TrialDefinition.RewardEntry();
        entry.item = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        entry.count = stack.getCount();
        return entry;
    }

    /** /tei trial list 之类的诊断用。 */
    public List<String> activeSessionIds()
    {
        List<String> result = new ArrayList<>();
        this.sessions.values().forEach(session -> result.add(session.playerName + " → " + session.definitionId));
        return result;
    }
}
