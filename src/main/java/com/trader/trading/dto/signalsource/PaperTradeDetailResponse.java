package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 模擬交易明細 — 訊號結果追蹤用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperTradeDetailResponse {

    private String tradeId;
    private String symbol;
    private String side;           // LONG / SHORT
    private String status;         // OPEN / CLOSED

    // 開倉
    private Double entryPrice;
    private Double entryQuantity;
    private LocalDateTime entryTime;

    // 平倉
    private Double exitPrice;
    private LocalDateTime exitTime;
    private String exitReason;     // STOP_LOSS / SIGNAL_CLOSE / TAKE_PROFIT / FAIL_SAFE

    // 風控
    private Double stopLoss;
    private String takeProfits;    // JSON 陣列
    private Integer leverage;

    // 盈虧
    private Double grossProfit;
    private Double commission;
    private Double netProfit;

    // AI 評分
    private Integer aiConfidence;
    private String aiReasoning;

    // 來源
    private String sourceAuthorName;

    // 持倉時長（秒）
    private Long durationSeconds;
}
