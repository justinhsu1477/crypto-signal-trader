package com.trader.shared.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * /api/discord-messages 請求 DTO — 來自 Python discord-monitor 的 per-message archive。
 *
 * <p>JSON 採 snake_case 鍵名與 Python 端一致；後端容忍未知欄位（Jackson 預設 + 顯式註解）。</p>
 *
 * <p>UPSERT 語意：以 message_id 為唯一鍵；同一 message_id 重複 POST 會 update parser_action /
 * parser_skipped_reason 欄位（其他欄位以首次 POST 為準）。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiscordRawMessageRequest {

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("channel_id")
    private String channelId;

    @JsonProperty("channel_name")
    private String channelName;

    @JsonProperty("guild_id")
    private String guildId;

    @JsonProperty("author_name")
    private String authorName;

    /**
     * Discord 端訊息發送時間（ISO 8601，須帶 timezone offset / Z）。
     * 用 OffsetDateTime 是因為 Discord 給的格式是 `...+00:00`，LocalDateTime 不吃 offset。
     * Service 端會轉成 AppConstants.ZONE_ID（Asia/Taipei）的 LocalDateTime 入庫。
     */
    @JsonProperty("message_timestamp")
    private OffsetDateTime messageTimestamp;

    private String content;

    @JsonProperty("has_attachments")
    private Boolean hasAttachments;

    @JsonProperty("attachment_count")
    private Integer attachmentCount;

    @JsonProperty("attachment_sha256")
    private String attachmentSha256;

    @JsonProperty("has_embed_images")
    private Boolean hasEmbedImages;

    @JsonProperty("has_reference")
    private Boolean hasReference;

    @JsonProperty("parser_action")
    private String parserAction;

    @JsonProperty("parser_skipped_reason")
    private String parserSkippedReason;
}
