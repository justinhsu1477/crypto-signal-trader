package com.trader.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSystemOverview {

    private int totalUsers;
    private int activeUsers;
    private int usersWithOpenPositions;

    private int totalOpenPositions;
    private long totalClosedTrades;
    private double totalNetProfit;
    private double todayNetProfit;
    private int todayTradeCount;

    private List<UserTradingSummary> userSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserTradingSummary {
        private String userId;
        private String email;
        private String name;
        private boolean enabled;
        private boolean autoTradeEnabled;
        private int openPositionCount;
        private long closedTradeCount;
        private double totalNetProfit;
        private double todayPnl;
        private int todayTradeCount;
    }
}
