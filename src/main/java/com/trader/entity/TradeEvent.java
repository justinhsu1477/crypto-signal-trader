package com.trader.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 事件日誌表 — 每個操作（下單、止損、平倉等）記錄一筆
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trade_events", indexes = {
        @Index(name = "idx_trade_id", columnList = "tradeId"),
        @Index(name = "idx_event_type", columnList = "eventType"),
        @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class TradeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                     // 自增主鍵

    private String tradeId;              // 關聯的交易 ID（對應 trades.tradeId）

    private String eventType;            // 事件類型：
                                         //   ENTRY_PLACED   — 入場掛單已下
                                         //   SL_PLACED      — 止損單已下
                                         //   MOVE_SL        — 移動止損
                                         //   CLOSE_PLACED   — 平倉掛單已下
                                         //   CANCEL         — 取消掛單
                                         //   FAIL_SAFE      — 安全機制觸發

    private LocalDateTime timestamp;     // 事件發生時間

    private String binanceOrderId;       // Binance 訂單號（如有）
    private String orderSide;            // BUY 或 SELL
    private String orderType;            // LIMIT / STOP_MARKET / MARKET
    private Double price;                // 相關價格
    private Double quantity;             // 相關數量

    private Boolean success;             // 操作是否成功
    private String errorMessage;         // 失敗原因（如有）

    @Column(length = 2000)
    private String detail;               // JSON 補充資料，例如 {"old_sl":68000,"new_sl":68300}

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now(TAIPEI_ZONE);
        }
    }
}
