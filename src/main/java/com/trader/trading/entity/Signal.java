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
        @Index(name = "idx_sig_created_at", columnList = "createdAt")
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
