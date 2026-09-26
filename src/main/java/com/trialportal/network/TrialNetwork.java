package com.trialportal.network;

import com.trialportal.TrialPortal;
import com.trialportal.arena.ArenaService;
import com.trialportal.trial.ChallengeDefinition;
import com.trialportal.trial.ChallengeLibrary;
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
 * 只有一条通道、两个包：服务端把可选副本清单推给客户端（开界面），客户端把「选了什么」回传（开局）。
 * <p>
 * 为什么不用 {@code openMenu} + 容器：这里没有槽位、没有物品，也不需要客户端持有服务端状态；
 * 一个 S2C 清单包 + 一个 C2C 选择包比注册 MenuType / ContainerData / IContainerFactory 少三层接线。
 * 清单必须从服务端推——{@code challenges/} 是服务端数据包，客户端那份不保证同步。
 */
public final class TrialNetwork
{
    /**
     * v2：难度条目从「只有 label 的字符串数组」改成 (key, label) 成对下发。
     * v1 让客户端按自己的顺序表用下标回推 key，两端各存一份顺序，改一档名字就会静默选错难度。
     */
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(TrialPortal.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private TrialNetwork() {}

    public static void register()
    {
        CHANNEL.registerMessage(0, OpenPicker.class, OpenPicker::encode, OpenPicker::decode, OpenPicker::handle);
        CHANNEL.registerMessage(1, StartChallenge.class, StartChallenge::encode, StartChallenge::decode,
                StartChallenge::handle);
    }

    /** 让客户端打开副本选择界面。 */
    public static void openPicker(ServerPlayer player)
    {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenPicker.of(ChallengeLibrary.ordered()));
    }

    /** 客户端确认选择 → 服务端真的开一局（不需要玩家对象，服务端从上下文取发送者）。 */
    public static void requestStart(String challengeId, String difficulty)
    {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new StartChallenge(challengeId, difficulty));
    }

    /** 一个可选项：回传给服务端的是 key，界面上显示的是 label。 */
    public record Option(String key, String label) {}

    /** 一行副本条目（界面只关心这些字段）。 */
    public record Entry(String id, String name, String iconItem, int seconds, List<Option> difficulties) {}

    /** S2C：副本清单。 */
    public record OpenPicker(List<Entry> entries)
    {
        public static OpenPicker of(List<ChallengeDefinition> challenges)
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
                        challenge.durationTicks / 20, options));
            }
            return new OpenPicker(entries);
        }

        static void encode(OpenPicker message, FriendlyByteBuf buffer)
        {
            buffer.writeVarInt(message.entries().size());
            for (Entry entry : message.entries())
            {
                buffer.writeUtf(entry.id());
                buffer.writeUtf(entry.name());
                buffer.writeUtf(entry.iconItem());
                buffer.writeVarInt(entry.seconds());
                buffer.writeVarInt(entry.difficulties().size());
                for (Option option : entry.difficulties())
                {
                    buffer.writeUtf(option.key());
                    buffer.writeUtf(option.label());
                }
            }
        }

        static OpenPicker decode(FriendlyByteBuf buffer)
        {
            List<Entry> entries = new ArrayList<>();
            int size = buffer.readVarInt();
            for (int i = 0; i < size; i++)
            {
                String id = buffer.readUtf();
                String name = buffer.readUtf();
                String icon = buffer.readUtf();
                int seconds = buffer.readVarInt();
                int optionCount = buffer.readVarInt();
                List<Option> options = new ArrayList<>();
                for (int j = 0; j < optionCount; j++)
                {
                    options.add(new Option(buffer.readUtf(), buffer.readUtf()));
                }
                entries.add(new Entry(id, name, icon, seconds, options));
            }
            return new OpenPicker(entries);
        }

        static void handle(OpenPicker message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> com.trialportal.client.ChallengePickerScreen.show(message.entries()));
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
}
