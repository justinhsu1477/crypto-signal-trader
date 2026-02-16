package com.trader.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 交易主表 — 一筆「開倉→平倉」= 一筆紀錄
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    private String tradeId;              // UUID，交易唯一識別碼

    private String symbol;               // 交易對，例如 BTCUSDT
    private String side;                 // 方向：LONG 或 SHORT

    // === 開倉資訊 ===
    private Double entryPrice;           // 實際入場價
    private Double entryQuantity;        // 入場數量（BTC）
    private LocalDateTime entryTime;     // 開倉時間
    private String entryOrderId;         // Binance 入場單訂單號

    // === 平倉資訊 ===
    private Double exitPrice;            // 實際出場價
    private Double exitQuantity;         // 出場數量
    private LocalDateTime exitTime;      // 平倉時間
    private String exitOrderId;          // Binance 平倉單訂單號

    // === 風控參數 ===
    private Double stopLoss;             // 設定的止損價
    private Integer leverage;            // 使用的槓桿倍數
    private Double riskAmount;           // 計畫風險金額 (1R = balance × riskPercent)

    // === 盈虧 ===
    private Double grossProfit;          // 毛利 = (exitPrice - entryPrice) × qty × 方向
    private Double entryCommission;      // 入場手續費 (USDT) — 開倉時即計算
    private Double commission;           // 總手續費 = 入場 + 出場 (USDT) — 平倉時更新
    private Double netProfit;            // 淨利 = 毛利 - 總手續費

    // === 狀態 ===
    private String status;               // OPEN=持倉中, CLOSED=已平倉, CANCELLED=已取消
    private String exitReason;           // 出場原因：STOP_LOSS / SIGNAL_CLOSE / MANUAL_CLOSE / FAIL_SAFE

    // === DCA 補倉 ===
    private Integer dcaCount;            // 補倉次數（0=首次入場，1=第一次補倉，2=第二次...）

    // === 去重 ===
    private String signalHash;           // 訊號去重雜湊 SHA256(symbol|side|entryPrice|stopLoss)

    // === 訊號來源 ===
    private String sourcePlatform;       // 來源平台: DISCORD, TELEGRAM, MANUAL, etc.
    private String sourceChannelId;      // 頻道 ID
    private String sourceGuildId;        // 伺服器 ID (Discord guild)
    private String sourceAuthorName;     // 訊號發送者
    private String sourceMessageId;      // 原始訊息 ID

    private LocalDateTime createdAt;     // 紀錄建立時間
    private LocalDateTime updatedAt;     // 最後更新時間

    // 事件查詢請使用 TradeEventRepository.findByTradeIdOrderByTimestampAsc(tradeId)

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(TAIPEI_ZONE);
        updatedAt = LocalDateTime.now(TAIPEI_ZONE);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(TAIPEI_ZONE);
    }
}
