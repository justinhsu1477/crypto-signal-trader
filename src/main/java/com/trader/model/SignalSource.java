package com.trader.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 訊號來源元資料 — 記錄訊號來自哪個平台/頻道
 *
 * 通用介面設計，支援 Discord、Telegram 或其他未來平台。
 *
 * 範例:
 * {
 *   "platform": "DISCORD",
 *   "channel_id": "1325133886509944983",
 *   "guild_id": "862188678876233748",
 *   "author_name": "陳哥",
 *   "message_id": "123456789"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalSource {

    /** 平台名稱: DISCORD, TELEGRAM, MANUAL, WEBHOOK, etc. */
    private String platform;

    /** 頻道/群組 ID（平台原生 ID） */
    @JsonProperty("channel_id")
    private String channelId;

    /** 頻道/群組名稱（方便人類閱讀，可選） */
    @JsonProperty("channel_name")
    private String channelName;

    /** 伺服器/工作區 ID（Discord guild_id, Telegram chat_id, etc.） */
    @JsonProperty("guild_id")
    private String guildId;

    /** 訊號發送者名稱 */
    @JsonProperty("author_name")
    private String authorName;

    /** 原始訊息 ID（用於溯源） */
    @JsonProperty("message_id")
    private String messageId;
}
