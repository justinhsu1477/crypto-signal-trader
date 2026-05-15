package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "signals", indexes = {
        @Index(name = "idx_sig_symbol", columnList = "symbol"),
        @Index(name = "idx_sig_action", columnList = "action"),
        @Index(name = "idx_sig_signal_hash", columnList = "signalHash"),
        @Index(name = "idx_sig_source_platform", columnList = "sourcePlatform"),
        @Index(name = "idx_sig_created_at", columnList = "createdAt"),
        @Index(name = "idx_sig_source_message_id", columnList = "sourceMessageId")
})
public class Signal {

    @Id
    private String signalId;

    // === 訊號來源 ===
    private String sourcePlatform;
    private String sourceChannelId;
    private String sourceChannelName;
    private String sourceGuildId;
    private String sourceAuthorName;
    private String sourceMessageId;

    /**
     * 觸發訊號的圖片附件 SHA-256（圖訊號 audit trail）
     * 圖訊號才會有值；文字訊號為 null。
     */
    @Column(name = "attachment_sha256")
    private String attachmentSha256;

    /**
     * 解析此訊號時用到的 custom_prompt 版本號。
     * Python parse 時 snapshot 自己 metadata 裡的值送回來，跟 admin 此後的改動無關。
     * null = 該來源沒設 custom_prompt 或走 regex fallback。
     */
    @Column(name = "custom_prompt_version")
    private Integer customPromptVersion;

    /**
     * 解析此訊號時用到的 custom_prompt 內容 SHA-256 前 16 hex。
     * 與 signal_sources.custom_prompt_sha256 對齊；不一致 = Python 和 Java view 出現分歧（bug 訊號）。
     */
    @Column(name = "custom_prompt_sha256", length = 16)
    private String customPromptSha256;

    // === 訊號內容 ===
    private String action;              // ENTRY, CLOSE, DCA, MOVE_SL, CANCEL, INFO
    private String symbol;
    private String side;                // LONG, SHORT
    private Double entryPriceLow;
    private Double entryPriceHigh;
    private Double stopLoss;
    @Column(columnDefinition = "TEXT")
    private String takeProfits;         // JSON array
    private Integer leverage;
    private Double closeRatio;
    private Double newStopLoss;
    private Double newTakeProfit;
    @Column(columnDefinition = "TEXT")
    private String rawMessage;

    // === 去重 & 執行結果 ===
    private String signalHash;
    private String executionStatus;     // EXECUTED, REJECTED, IGNORED, FAILED
    private String rejectionReason;
    private String tradeId;             // 關聯的交易 ID（nullable）

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
