package com.trader.papertrade.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 單一訊號源在 paper trading 上的績效指標。
 *
 * <p>所有指標**只從 simulated=true + status=CLOSED 的 trades 算出**，不會跟 real trading 混淆。
 *
 * <h3>指標定義</h3>
 * <ul>
 *   <li><b>winRate</b>: wins / closedTrades, 範圍 [0, 1]</li>
 *   <li><b>profitFactor</b>: sum(wins) / |sum(losses)|, > 1.0 才算有 edge</li>
 *   <li><b>maxDrawdownPct</b>: 自 equity 高點到低點最大跌幅, [0, 1]，0.20 = 跌 20%</li>
 *   <li><b>sharpeRatio</b>: annualized = avg(daily_return) / stddev(daily_return) × sqrt(365)</li>
 *   <li><b>expectancy</b>: winRate × avgWin + (1-winRate) × avgLoss（每筆預期盈虧）</li>
 * </ul>
 *
 * <p>給 {@code PaperPromotionEvaluator} 用來判斷是否建議升 AUTO。
 */
@Data
@Builder
public class SourcePerformanceMetrics {

    private Long sourceId;              // signal_sources.id
    private String sourceName;          // e.g. "fengge"
    private String displayName;         // e.g. "峰哥"
    private String channelId;           // source_channel_id (DB join key)

    // 基本統計
    private int closedTrades;
    private int wins;
    private int losses;
    private double winRate;             // [0, 1]
    private double totalPnl;
    private double avgPnl;
    private double avgWin;              // 平均獲利金額（只算 wins）
    private double avgLoss;             // 平均虧損金額（只算 losses，已是負數）

    // 風險指標
    private double profitFactor;        // sum(wins) / |sum(losses)|
    private double maxDrawdownPct;      // 最大回撤百分比 [0, 1]
    private double sharpeRatio;         // annualized
    private double expectancy;          // 每筆預期盈虧

    // 時間範圍
    private LocalDateTime firstTradeAt;
    private LocalDateTime lastTradeAt;
    private long periodDays;            // lastTradeAt - firstTradeAt
}
