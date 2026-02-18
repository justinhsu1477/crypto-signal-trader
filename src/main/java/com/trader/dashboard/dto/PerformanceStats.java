package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 績效統計 DTO
 *
 * 提供前端繪製盈虧曲線和績效指標，
 * 包含訊號來源排名、出場原因分布，
 * 以及進階分析：回撤、連勝連敗、幣種/多空/時間分組、DCA 效果。
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

    /** 盈虧曲線資料點（含回撤） */
    private List<PnlDataPoint> pnlCurve;

    /** 幣種別績效 */
    private List<SymbolStats> symbolStats;

    /** 多空對比 */
    private SideComparison sideComparison;

    /** 週統計 */
    private List<WeeklyStats> weeklyStats;

    /** 月統計 */
    private List<MonthlyStats> monthlyStats;

    /** 星期幾績效 */
    private List<DayOfWeekStats> dayOfWeekStats;

    /** DCA 補倉分析 */
    private DcaAnalysis dcaAnalysis;

    // ==================== Summary ====================

    @Data
    @Builder
    public static class Summary {
        /** 總交易筆數 */
        private long totalTrades;
        /** 獲利筆數 */
        private long winningTrades;
        /** 虧損筆數 */
        private long losingTrades;
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
        /** 平均獲利金額 (USDT) — 僅盈利交易 */
        private double avgWin;
        /** 平均虧損金額 (USDT) — 僅虧損交易，負數 */
        private double avgLoss;
        /** 風報比 = |avgWin| / |avgLoss| */
        private double riskRewardRatio;
        /** 期望值 = (winRate × avgWin) - (lossRate × |avgLoss|) */
        private double expectancy;
        /** 最大連勝 */
        private int maxConsecutiveWins;
        /** 最大連敗 */
        private int maxConsecutiveLosses;
        /** 最大回撤 (USDT, 負數) */
        private double maxDrawdown;
        /** 最大回撤 (%, 負數) */
        private double maxDrawdownPercent;
        /** 最大回撤持續天數 */
        private int maxDrawdownDays;
        /** 平均持倉時間 (小時) */
        private double avgHoldingHours;
    }

    // ==================== 既有 inner classes ====================

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
        /** 當日回撤金額 (USDT, 負數或零) */
        private double drawdown;
        /** 當日回撤百分比 (%, 負數或零) */
        private double drawdownPercent;
    }

    // ==================== 新增 inner classes ====================

    /** 幣種別績效統計 */
    @Data
    @Builder
    public static class SymbolStats {
        private String symbol;
        private long trades;
        private long wins;
        private double winRate;
        private double netProfit;
        private double avgProfit;
    }

    /** 多空對比 */
    @Data
    @Builder
    public static class SideComparison {
        private SideStats longStats;
        private SideStats shortStats;
    }

    /** 單方向（LONG 或 SHORT）統計 */
    @Data
    @Builder
    public static class SideStats {
        private long trades;
        private long wins;
        private double winRate;
        private double netProfit;
        private double avgProfit;
        private double profitFactor;
    }

    /** 週統計 */
    @Data
    @Builder
    public static class WeeklyStats {
        /** 週起始日 yyyy-MM-dd */
        private String weekStart;
        /** 週結束日 yyyy-MM-dd */
        private String weekEnd;
        private long trades;
        private double netProfit;
        private double winRate;
    }

    /** 月統計 */
    @Data
    @Builder
    public static class MonthlyStats {
        /** 月份 yyyy-MM */
        private String month;
        private long trades;
        private double netProfit;
        private double winRate;
    }

    /** 星期幾績效 */
    @Data
    @Builder
    public static class DayOfWeekStats {
        /** 星期幾 (MONDAY ~ SUNDAY) */
        private String dayOfWeek;
        private long trades;
        private double netProfit;
        private double winRate;
    }

    /** DCA 補倉效果分析 */
    @Data
    @Builder
    public static class DcaAnalysis {
        /** 無補倉交易數 */
        private long noDcaTrades;
        /** 無補倉勝率 (%) */
        private double noDcaWinRate;
        /** 無補倉平均獲利 (USDT) */
        private double noDcaAvgProfit;
        /** 有補倉交易數 */
        private long dcaTrades;
        /** 補倉勝率 (%) */
        private double dcaWinRate;
        /** 補倉平均獲利 (USDT) */
        private double dcaAvgProfit;
    }
}
