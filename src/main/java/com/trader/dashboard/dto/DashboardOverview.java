package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Dashboard 首頁摘要 DTO
 *
 * 前端首頁一次取得所有需要的資料
 */
@Data
@Builder
public class DashboardOverview {

    /** 目前持倉數量 */
    private int openPositions;

    /** 今日已實現盈虧 (USDT) */
    private double todayPnl;

    /** 今日交易筆數 */
    private int todayTrades;

    /** 訂閱方案名稱（"free", "basic", "pro"） */
    private String subscriptionPlan;

    /** 訂閱是否有效 */
    private boolean subscriptionActive;

    /** 目前持倉摘要 */
    private List<OpenPositionSummary> positions;

    @Data
    @Builder
    public static class OpenPositionSummary {
        private String symbol;
        private String side;
        private double entryPrice;
        private double stopLoss;
        private double unrealizedPnl;
    }
}
