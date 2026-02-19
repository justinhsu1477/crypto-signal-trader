package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Dashboard 首頁摘要 DTO
 *
 * 前端首頁一次取得所有需要的資料，
 * 包含帳戶概況、風控預算、訂閱狀態、持倉詳情。
 */
@Data
@Builder
public class DashboardOverview {

    /** 帳戶概況 */
    private AccountSummary account;

    /** 風控預算（每日虧損熔斷） */
    private RiskBudget riskBudget;

    /** 訂閱狀態 */
    private SubscriptionInfo subscription;

    /** 自動跟單是否啟用 */
    private boolean autoTradeEnabled;

    /** 目前持倉列表 */
    private List<OpenPositionSummary> positions;

    @Data
    @Builder
    public static class AccountSummary {
        /** 可用餘額 (USDT) */
        private double availableBalance;
        /** 持倉數量 */
        private int openPositionCount;
        /** 今日已實現盈虧 (USDT) */
        private double todayPnl;
        /** 今日交易筆數 */
        private int todayTradeCount;
    }

    @Data
    @Builder
    public static class RiskBudget {
        /** 每日虧損上限 (USDT) */
        private double dailyLossLimit;
        /** 今日已用虧損額 (USDT)，正數表示已虧損金額 */
        private double todayLossUsed;
        /** 剩餘風險額度 (USDT) */
        private double remainingBudget;
        /** 熔斷是否觸發 */
        private boolean circuitBreakerActive;
    }

    @Data
    @Builder
    public static class SubscriptionInfo {
        /** 方案名稱 */
        private String plan;
        /** 是否有效 */
        private boolean active;
        /** 到期日 */
        private String expiresAt;
    }

    @Data
    @Builder
    public static class OpenPositionSummary {
        /** 交易對 */
        private String symbol;
        /** 方向 LONG/SHORT */
        private String side;
        /** 入場價 */
        private double entryPrice;
        /** 止損價 */
        private Double stopLoss;
        /** 計畫風險金額 */
        private Double riskAmount;
        /** DCA 補倉次數 */
        private Integer dcaCount;
        /** 訊號來源 */
        private String signalSource;
        /** 入場時間 */
        private String entryTime;
    }
}
