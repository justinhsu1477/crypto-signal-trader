package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 訊號來源績效統計 — 從 trades 表按 source_channel_id 聚合計算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalSourcePerformanceDto {

    private Long sourceId;
    private String name;
    private String displayName;
    private long tradeCount;
    private long winCount;
    private double winRate;
    private double totalPnl;
    private double avgPnl;
}
