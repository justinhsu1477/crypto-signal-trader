package com.trader.chatbot.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用戶發送客服訊息事件（支援 LINE / Discord 多頻道）
 *
 * 由 LineLinkingService / DiscordBotListener 發布，
 * 由 ChatbotMessageHandler 監聽（chatbot 模組）。
 * 用 ApplicationEvent 解耦模組依賴方向。
 */
@Getter
public class ChatMessageEvent extends ApplicationEvent {

    private final String userId;
    private final String channel;        // "LINE" / "DISCORD"
    private final String channelUserId;  // LINE userId 或 Discord userId
    private final String text;
    private final String replyChannelId; // Discord 頻道回覆用（null = DM / LINE）

    /**
     * @deprecated 使用 {@link #ChatMessageEvent(Object, String, String, String, String)} 取代
     */
    @Deprecated
    private final String lineUserId;

    public ChatMessageEvent(Object source, String userId, String channel, String channelUserId, String text) {
        this(source, userId, channel, channelUserId, text, null);
    }

    /**
     * Discord 頻道 @mention 用（帶 replyChannelId）
     */
    public ChatMessageEvent(Object source, String userId, String channel, String channelUserId,
                            String text, String replyChannelId) {
        super(source);
        this.userId = userId;
        this.channel = channel;
        this.channelUserId = channelUserId;
        this.text = text;
        this.replyChannelId = replyChannelId;
        this.lineUserId = "LINE".equals(channel) ? channelUserId : null;
    }

    /**
     * @deprecated 使用 {@link #ChatMessageEvent(Object, String, String, String, String)} 取代
     */
    @Deprecated
    public ChatMessageEvent(Object source, String userId, String lineUserId, String text) {
        this(source, userId, "LINE", lineUserId, text);
    }
}
