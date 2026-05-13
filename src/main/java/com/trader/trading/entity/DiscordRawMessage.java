package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 每則 Discord 訊息封存 — 用於 audit / 漏單偵測 / eval-harness 訓練資料。
 *
 * <p>不論 AI 判讀結果（ENTRY/CLOSE/INFO/SKIPPED），每則通過 channel/guild/author
 * 過濾的訊息都會在此表留下一筆紀錄；若後續實際產生 Signal，會回填 signal_id 形成關聯。</p>
 *
 * <p>Indexed by (source_channel_id, message_timestamp DESC),
 * (source_author_name, message_timestamp DESC), signal_id where not null,
 * and a partial index for the missed-signal audit query
 * (author_name + signal_id IS NULL + parser_action IS NULL).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "discord_raw_messages")
public class DiscordRawMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Discord message ID — 唯一鍵，防止重複封存 */
    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Column(name = "source_platform", nullable = false, length = 32)
    @Builder.Default
    private String sourcePlatform = "DISCORD";

    @Column(name = "source_channel_id", nullable = false, length = 64)
    private String sourceChannelId;

    @Column(name = "source_channel_name", length = 255)
    private String sourceChannelName;

    @Column(name = "source_guild_id", length = 64)
    private String sourceGuildId;

    @Column(name = "source_author_name", length = 255)
    private String sourceAuthorName;

    /** Discord 端訊息發送時間（非 archive 寫入時間） */
    @Column(name = "message_timestamp", nullable = false)
    private LocalDateTime messageTimestamp;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "has_attachments", nullable = false)
    @Builder.Default
    private Boolean hasAttachments = false;

    @Column(name = "attachment_count", nullable = false)
    @Builder.Default
    private Integer attachmentCount = 0;

    /** 第一張圖片附件的 SHA-256（沒圖則為 null） */
    @Column(name = "attachment_sha256", length = 64)
    private String attachmentSha256;

    @Column(name = "has_embed_images", nullable = false)
    @Builder.Default
    private Boolean hasEmbedImages = false;

    /** 是否為 reply / quote（forward 也算） */
    @Column(name = "has_reference", nullable = false)
    @Builder.Default
    private Boolean hasReference = false;

    /** AI parser 結果：ENTRY / CLOSE / MOVE_SL / CANCEL / INFO / null=未處理 */
    @Column(name = "parser_action", length = 32)
    private String parserAction;

    /** AI parser 略過原因：BLACKLIST / DEDUP / EMPTY / FILTERED / null */
    @Column(name = "parser_skipped_reason", length = 64)
    private String parserSkippedReason;

    /** 連結到對應的 signals.signal_id（若有產生訊號） */
    @Column(name = "signal_id", length = 64)
    private String signalId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 樂觀鎖版本欄位 — 偵測同列並發更新（Python parser_action 更新 vs Java linkDiscordRawMessage）。
     * stale 寫入會被 JPA 拒絕並拋 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}。
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
        }
    }
}
