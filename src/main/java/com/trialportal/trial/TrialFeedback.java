package com.trialportal.trial;

import com.trialportal.config.TrialConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * 所有玩家播报的唯一出口。announce_* 是否同时进聊天栏由 general.announce_in_chat 决定。
 */
public final class TrialFeedback
{
    private TrialFeedback() {}

    public static void title(ServerPlayer player, String title, String subtitle)
    {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 10));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title).withStyle(ChatFormatting.GOLD)));
        if (subtitle != null && !subtitle.isBlank())
        {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle).withStyle(ChatFormatting.YELLOW)));
        }
    }

    public static void actionbar(ServerPlayer player, String text)
    {
        player.displayClientMessage(Component.literal(text), true);
    }

    public static void chat(ServerPlayer player, String text)
    {
        if (Boolean.TRUE.equals(TrialConfig.GENERAL_ANNOUNCE_IN_CHAT.get()))
        {
            player.displayClientMessage(Component.literal(text), false);
        }
    }

    /** 关键事件：大标题 + 聊天栏各一份，避免玩家只看其中一个就漏掉信息。 */
    public static void announce(ServerPlayer player, String title, String subtitle, String chatText)
    {
        title(player, title, subtitle);
        chat(player, chatText == null ? title : chatText);
    }

    public static void failure(ServerPlayer player, String text)
    {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.RED), true);
    }
}
