package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 訊號來源績效統計 — 從 trades 表按 source_channel_id 聚合計算
 * 包含真實交易和模擬交易（SHADOW 模式）兩組統計
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalSourcePerformanceDto {

    private Long sourceId;
    private String name;
    private String displayName;
    private String tradeMode;       // AUTO / SHADOW / MANUAL

    // 真實交易績效
    private long tradeCount;
    private long winCount;
    private double winRate;
    private double totalPnl;
    private double avgPnl;
    private double maxWin;
    private double maxLoss;
    private double profitFactor;            // grossWins / grossLosses
    private int maxConsecutiveWins;
    private int maxConsecutiveLosses;

    // 模擬交易績效（SHADOW 頻道用）
    private long paperTradeCount;
    private long paperWinCount;
    private double paperWinRate;
    private double paperTotalPnl;
    private double paperAvgPnl;
    private double paperMaxWin;
    private double paperMaxLoss;
    private double paperProfitFactor;
    private int paperMaxConsecutiveWins;
    private int paperMaxConsecutiveLosses;
}
