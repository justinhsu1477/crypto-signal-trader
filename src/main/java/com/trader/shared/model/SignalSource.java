package com.trader.shared.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 *   "message_id": "123456789",
 *   "attachment": { "url": "...", "sha256": "...", "size": 123 }
 * }
 *
 * 注意：Python 端送出 source 時可能多帶尚未在 Java 端落地的欄位（source_name、
 * display_name、trade_mode、risk_multiplier 等）。本 DTO 用 @JsonIgnoreProperties
 * 容忍未知欄位，與 Spring Boot 預設行為一致，避免欄位 drift 造成整個請求被拒。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * 觸發訊號的圖片附件 SHA-256（圖訊號 audit trail）
     *
     * Python 端在圖訊號觸發時於 source.attachment.sha256 提供，
     * 用於追蹤「這筆交易是哪張圖觸發的」。文字訊號為 null。
     *
     * 此欄位可由兩種方式注入：
     * 1. 直接 @JsonProperty("attachment_sha256") — 舊式 flat 結構
     * 2. 透過 setAttachment(Attachment) — Python 送出的 nested attachment 物件
     */
    @JsonProperty("attachment_sha256")
    private String attachmentSha256;

    /**
     * Python 端 nested attachment 物件 setter。
     *
     * Python 端送的是 source.attachment = { url, filename, content_type, sha256, size }，
     * 我們只關心 sha256（其餘 audit 用，但 SignalSource 不持久化）。
     * 此 setter 讓 Jackson 能將 nested attachment.sha256 抽進 flat attachmentSha256，
     * 不再依賴 controller 端手動 Map 解析。
     */
    @JsonProperty("attachment")
    public void setAttachment(Attachment attachment) {
        if (attachment != null && attachment.getSha256() != null) {
            this.attachmentSha256 = attachment.getSha256();
        }
    }

    /**
     * Nested attachment payload（從 Python 端送入）。
     * 只暴露 sha256 給 SignalSource 使用；其餘欄位 url/filename/content_type/size
     * 可保留於此 DTO 但不寫進 Signal entity（避免 schema 爆炸）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attachment {
        private String url;
        private String filename;
        @JsonProperty("content_type")
        private String contentType;
        private String sha256;
        private Long size;
    }
}
