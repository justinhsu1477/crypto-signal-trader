package com.trader.chatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RabbitMQ 客服訊息請求（投遞到 chatbot.request queue）
 * 支援 LINE / Discord 多頻道
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotRequest implements Serializable {
    private String userId;
    private String channel;        // "LINE" / "DISCORD"
    private String channelUserId;  // LINE userId 或 Discord userId
    private String message;
    private String replyChannelId; // Discord 頻道回覆用（null = DM / LINE）
    private String lineReplyToken; // LINE Reply API token（30 秒過期，優先使用）

    /**
     * @deprecated 使用 {@link #getChannelUserId()} 取代
     */
    @Deprecated
    private String lineUserId;
}
