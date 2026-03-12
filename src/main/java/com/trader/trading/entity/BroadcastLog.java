package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 廣播跟單紀錄 — 每次廣播執行的結果審計
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "broadcast_logs", indexes = {
    @Index(name = "idx_bl_created_at", columnList = "created_at")
})
public class BroadcastLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String signalAction;     // ENTRY, CLOSE, MOVE_SL, CANCEL
    private String symbol;           // 交易對
    private String side;             // LONG / SHORT
    private Double entryPrice;
    private Double stopLoss;
    private Double takeProfit;
    private Double closeRatio;
    private Double newStopLoss;
    private Double newTakeProfit;
    private Boolean isDca;
    private String sourceAuthor;     // 訊號來源

    private int totalUsers;          // 廣播目標用戶數
    private int successCount;
    private int failCount;
    private int skippedNoSub;        // 跳過：無訂閱
    private int skippedNoKey;        // 跳過：無 API Key
    private int skippedNotAssigned;  // 跳過：未綁定此來源
    private Long sourceId;           // 訊號來源 ID（nullable）

    private String status;           // COMPLETED, INTERRUPTED

    @Column(columnDefinition = "TEXT")
    private String userResults;      // JSON: per-user 執行結果

    private Integer aiConfidence;    // AI 信心分數
    @Column(columnDefinition = "TEXT")
    private String aiReasoning;      // AI 評分理由

    private Long durationMs;         // 廣播耗時（毫秒）

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
