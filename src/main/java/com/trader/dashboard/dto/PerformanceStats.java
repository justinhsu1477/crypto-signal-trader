package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 績效統計 DTO
 *
 * 提供前端繪製盈虧曲線和績效指標，
 * 包含訊號來源排名和出場原因分布。
 */
@Data
@Builder
public class PerformanceStats {

    /** 績效摘要 */
    private Summary summary;

    /** 出場原因分布 */
    private Map<String, Long> exitReasonBreakdown;

    /** 訊號來源排名 */
    private List<SignalSourceStats> signalSourceRanking;

    /** 盈虧曲線資料點 */
    private List<PnlDataPoint> pnlCurve;

    @Data
    @Builder
    public static class Summary {
        /** 總交易筆數 */
        private long totalTrades;
        /** 獲利筆數 */
        private long winningTrades;
        /** 勝率 (%) */
        private double winRate;
        /** Profit Factor */
        private double profitFactor;
        /** 總淨利 (USDT) */
        private double totalNetProfit;
        /** 平均每筆盈虧 (USDT) */
        private double avgProfitPerTrade;
        /** 總手續費 (USDT) */
        private double totalCommission;
        /** 最大單筆獲利 */
        private double maxWin;
        /** 最大單筆虧損 */
        private double maxLoss;
    }

    @Data
    @Builder
    public static class SignalSourceStats {
        /** 來源名稱 */
        private String source;
        /** 交易筆數 */
        private long trades;
        /** 勝率 (%) */
        private double winRate;
        /** 淨利 (USDT) */
        private double netProfit;
    }

    @Data
    @Builder
    public static class PnlDataPoint {
        /** 日期 (yyyy-MM-dd) */
        private String date;
        /** 當日淨利 */
        private double dailyPnl;
        /** 累計淨利 */
        private double cumulativePnl;
    }
}
