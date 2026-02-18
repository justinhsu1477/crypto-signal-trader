package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 績效統計 DTO
 *
 * 提供前端繪製盈虧曲線和績效指標
 */
@Data
@Builder
public class PerformanceStats {

    /** 總交易筆數 */
    private long totalTrades;

    /** 勝率 (%) */
    private double winRate;

    /** Profit Factor (總獲利 / 總虧損) */
    private double profitFactor;

    /** 總淨利 (USDT) */
    private double totalNetProfit;

    /** 平均每筆盈虧 (USDT) */
    private double avgProfitPerTrade;

    /** 最大單筆獲利 */
    private double maxWin;

    /** 最大單筆虧損 */
    private double maxLoss;

    /** 盈虧曲線資料點（前端用來畫折線圖） */
    private List<PnlDataPoint> pnlCurve;

    @Data
    @Builder
    public static class PnlDataPoint {
        /** 日期 (yyyy-MM-dd) */
        private String date;
        /** 累計淨利 */
        private double cumulativePnl;
        /** 當日淨利 */
        private double dailyPnl;
    }
}
