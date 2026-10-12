package com.trialportal.network;

import com.trialportal.TrialPortal;
import com.trialportal.arena.ArenaService;
import com.trialportal.trial.ChallengeDefinition;
import com.trialportal.trial.ChallengeLibrary;
import com.trialportal.trial.DefenseDefinition;
import com.trialportal.trial.DefenseLibrary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 只有一条通道：服务端把可选项清单推给客户端（开界面），客户端把「选了什么」回传（开局）。
 * <p>
 * 为什么不用 {@code openMenu} + 容器：这里没有槽位、没有物品，也不需要客户端持有服务端状态；
 * 一个 S2C 清单包 + 一个 C2S 选择包比注册 MenuType / ContainerData / IContainerFactory 少三层接线。
 * 清单必须从服务端推——{@code challenges/} 与 {@code defenses/} 是服务端数据包，客户端那份不保证同步。
 * <p>
 * 闯关与守塔<b>共用同一张界面和同一套编解码</b>：两者差别只有「有没有可选项（难度档）」和
 * 「确认后回传哪种开局」，写两份选单迟早会一边改了另一边没改（这条是被同一份清单漂过两次之后定的）。
 */
public final class TrialNetwork
{
    /**
     * v2：难度条目从「只有 label 的字符串数组」改成 (key, label) 成对下发。
     * v1 让客户端按自己的顺序表用下标回推 key，两端各存一份顺序，改一档名字就会静默选错难度。
     * <p>
     * v3：清单加了「这是哪种玩法」，行摘要从「多少秒」改成一整句文本（守塔那行要说波数与塔血）。
     * 形状变了就必须换版本号——否则新旧两端连得上、界面按错的字段画。
     */
    private static final String PROTOCOL = "3";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private TrialNetwork() {}

    public static void register()
    {
        CHANNEL.registerMessage(0, OpenPicker.class, OpenPicker::encode, OpenPicker::decode, OpenPicker::handle);
        CHANNEL.registerMessage(1, StartChallenge.class, StartChallenge::encode, StartChallenge::decode,
                StartChallenge::handle);
        CHANNEL.registerMessage(2, StartDefense.class, StartDefense::encode, StartDefense::decode,
                StartDefense::handle);
    }

    /** 让客户端打开副本选择界面。 */
    public static void openPicker(ServerPlayer player)
    {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                OpenPicker.ofChallenges(ChallengeLibrary.ordered()));
    }

    /** 让客户端打开守塔关卡选择界面。 */
    public static void openDefensePicker(ServerPlayer player)
    {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                OpenPicker.ofDefenses(DefenseLibrary.ordered()));
    }

    /** 客户端确认选择 → 服务端真的开一局（不需要玩家对象，服务端从上下文取发送者）。 */
    public static void requestStart(String challengeId, String difficulty)
    {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new StartChallenge(challengeId, difficulty));
    }

    public static void requestStartDefense(String defenseId)
    {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new StartDefense(defenseId));
    }

    /** 选单两种形态：有没有可选项（难度档）、确认后走哪条回传。 */
    public enum Kind
    {
        CHALLENGE, DEFENSE
    }

    /** 一个可选项：回传给服务端的是 key，界面上显示的是 label。 */
    public record Option(String key, String label) {}

    /**
     * 一行可选项。{@code detail} 是服务端拼好的一句摘要（「时限 120 秒」／「4 波 · 塔血 320」），
     * 客户端不参与解释数字——两种玩法要说的不是一回事，塞一个 int 进去迟早要为守塔再开一个字段。
     */
    public record Entry(String id, String name, String iconItem, String detail, List<Option> options) {}

    /**
     * 点「完成」到底能不能发出去：闯关必须选到一档（三档都在数据里，缺档时点下去不该静默），
     * 守塔没有可选项、选了一行就可确认。空 ID 是清单为空时那行占位，永远不能确认。
     * 放在这一层而不是界面里，是因为无头的门读不到客户端类，而这条判据恰恰会静默吞掉点击。
     */
    public static boolean confirmable(Kind kind, Entry entry, int optionIndex)
    {
        if (entry.id().isEmpty())
        {
            return false;
        }
        return kind == Kind.DEFENSE || optionIndex < entry.options().size();
    }

    /**
     * 清单包原样走一遍线上再读回来。之所以公开这一个口子：编码与解码是分别写的两段，
     * 字段顺序错一位不会有任何报错，只会让玩家看到「4 波」写进时限那一格——这种缺陷只能这样抓。
     */
    public static OpenPicker roundTrip(OpenPicker message)
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        OpenPicker.encode(message, buffer);
        OpenPicker back = OpenPicker.decode(buffer);
        buffer.release();
        return back;
    }

    /** S2C：可选清单。 */
    public record OpenPicker(Kind kind, List<Entry> entries)
    {
        public static OpenPicker ofChallenges(List<ChallengeDefinition> challenges)
        {
            List<Entry> entries = new ArrayList<>();
            for (ChallengeDefinition challenge : challenges)
            {
                List<Option> options = new ArrayList<>();
                for (String key : ChallengeDefinition.DIFFICULTY_KEYS)
                {
                    ChallengeDefinition.Difficulty difficulty = challenge.difficulty(key);
                    if (difficulty != null)
                    {
                        options.add(new Option(key, difficulty.displayNameOrDefault(key)));
                    }
                }
                entries.add(new Entry(challenge.id(), challenge.displayNameOrDefault(), challenge.iconItem,
                        "时限 " + challenge.durationTicks / 20 + " 秒", options));
            }
            return new OpenPicker(Kind.CHALLENGE, entries);
        }

        public static OpenPicker ofDefenses(List<DefenseDefinition> defenses)
        {
            List<Entry> entries = new ArrayList<>();
            for (DefenseDefinition defense : defenses)
            {
                // 守塔没有难度档：关卡之间的差别就写在 detail 里，选一行就是选一场
                entries.add(new Entry(defense.id(), defense.displayNameOrDefault(), defense.iconItem,
                        defense.finiteWaveCount() + " 波 · 塔血 " + (long) defense.towerHealth
                                + " · 结界半径 " + defense.wardRadius + " 格", List.of()));
            }
            return new OpenPicker(Kind.DEFENSE, entries);
        }

        static void encode(OpenPicker message, FriendlyByteBuf buffer)
        {
            buffer.writeUtf(message.kind().name());
            buffer.writeVarInt(message.entries().size());
            for (Entry entry : message.entries())
            {
                buffer.writeUtf(entry.id());
                buffer.writeUtf(entry.name());
                buffer.writeUtf(entry.iconItem());
                buffer.writeUtf(entry.detail());
                buffer.writeVarInt(entry.options().size());
                for (Option option : entry.options())
                {
                    buffer.writeUtf(option.key());
                    buffer.writeUtf(option.label());
                }
            }
        }

        static OpenPicker decode(FriendlyByteBuf buffer)
        {
            Kind kind = Kind.valueOf(buffer.readUtf());
            List<Entry> entries = new ArrayList<>();
            int size = buffer.readVarInt();
            for (int i = 0; i < size; i++)
            {
                String id = buffer.readUtf();
                String name = buffer.readUtf();
                String icon = buffer.readUtf();
                String detail = buffer.readUtf();
                int optionCount = buffer.readVarInt();
                List<Option> options = new ArrayList<>();
                for (int j = 0; j < optionCount; j++)
                {
                    options.add(new Option(buffer.readUtf(), buffer.readUtf()));
                }
                entries.add(new Entry(id, name, icon, detail, options));
            }
            return new OpenPicker(kind, entries);
        }

        static void handle(OpenPicker message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> com.trialportal.client.ChallengePickerScreen.show(message));
            context.setPacketHandled(true);
        }
    }

    /** C2S：玩家选了哪个副本的哪档难度。服务端按 ID 重新查表，不信客户端的展示数据。 */
    public record StartChallenge(String challengeId, String difficulty)
    {
        static void encode(StartChallenge message, FriendlyByteBuf buffer)
        {
            buffer.writeUtf(message.challengeId());
            buffer.writeUtf(message.difficulty());
        }

        static StartChallenge decode(FriendlyByteBuf buffer)
        {
            return new StartChallenge(buffer.readUtf(), buffer.readUtf());
        }

        static void handle(StartChallenge message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() ->
            {
                ServerPlayer player = context.getSender();
                if (player == null)
                {
                    return;
                }
                ChallengeDefinition challenge = ChallengeLibrary.get(message.challengeId());
                String difficulty = ChallengeLibrary.normalizeDifficulty(message.difficulty());
                if (challenge == null || difficulty == null || challenge.difficulty(difficulty) == null)
                {
                    // 客户端点了个不存在的组合（多半是 /reload 换过包），给可见反馈而不是默默无事发生
                    com.trialportal.trial.TrialFeedback.failure(player,
                            "副本或难度不存在：" + message.challengeId() + " / " + message.difficulty() + "，请重新打开界面");
                    return;
                }
                ArenaService.beginDungeon(player, challenge, difficulty);
            });
            context.setPacketHandled(true);
        }
    }

    /** C2S：玩家选了哪一场守塔关卡。同样只回传 ID，内容以服务端那份为准。 */
    public record StartDefense(String defenseId)
    {
        static void encode(StartDefense message, FriendlyByteBuf buffer)
        {
            buffer.writeUtf(message.defenseId());
        }

        static StartDefense decode(FriendlyByteBuf buffer)
        {
            return new StartDefense(buffer.readUtf());
        }

        static void handle(StartDefense message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() ->
            {
                ServerPlayer player = context.getSender();
                if (player == null)
                {
                    return;
                }
                DefenseDefinition defense = DefenseLibrary.get(message.defenseId());
                if (defense == null)
                {
                    com.trialportal.trial.TrialFeedback.failure(player,
                            "守塔关卡不存在：" + message.defenseId() + "，请重新打开界面");
                    return;
                }
                ArenaService.beginDefense(player, defense);
            });
            context.setPacketHandled(true);
        }
    }
}
