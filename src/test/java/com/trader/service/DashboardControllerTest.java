package com.trader.service;

import com.trader.dashboard.controller.DashboardController;
import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DashboardController 單元測試
 *
 * 驗證 Controller 層的行為：
 * - 正確呼叫 Service 方法
 * - 正確傳遞參數（userId、days、page、size）
 * - 正確回傳 200 ResponseEntity
 * - 未登入時拋出 IllegalStateException
 */
class DashboardControllerTest {

    private DashboardService dashboardService;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        controller = new DashboardController(dashboardService);
    }

    // ==================== /overview ====================

    @Nested
    @DisplayName("GET /api/dashboard/overview")
    class Overview {

        @Test
        @DisplayName("已登入 → 回傳 200 + DashboardOverview")
        void overviewSuccess() {
            DashboardOverview overview = DashboardOverview.builder()
                    .account(DashboardOverview.AccountSummary.builder()
                            .availableBalance(1000.0)
                            .openPositionCount(2)
                            .todayPnl(50.0)
                            .todayTradeCount(3)
                            .build())
                    .riskBudget(DashboardOverview.RiskBudget.builder()
                            .dailyLossLimit(2000.0)
                            .todayLossUsed(100.0)
                            .remainingBudget(1900.0)
                            .circuitBreakerActive(false)
                            .build())
                    .subscription(DashboardOverview.SubscriptionInfo.builder()
                            .plan("pro").active(true).build())
                    .positions(List.of())
                    .build();

            when(dashboardService.getOverview("user-123")).thenReturn(overview);

            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");

                ResponseEntity<DashboardOverview> response = controller.getOverview();

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getAccount().getAvailableBalance()).isEqualTo(1000.0);
                assertThat(response.getBody().getAccount().getOpenPositionCount()).isEqualTo(2);
                assertThat(response.getBody().getRiskBudget().isCircuitBreakerActive()).isFalse();
                assertThat(response.getBody().getSubscription().getPlan()).isEqualTo("pro");

                verify(dashboardService).getOverview("user-123");
            }
        }

        @Test
        @DisplayName("未登入 → 拋出 IllegalStateException")
        void overviewNotLoggedIn() {
            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId)
                        .thenThrow(new IllegalStateException("用戶未登入"));

                assertThatThrownBy(() -> controller.getOverview())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("用戶未登入");

                verify(dashboardService, never()).getOverview(anyString());
            }
        }
    }

    // ==================== /performance ====================

    @Nested
    @DisplayName("GET /api/dashboard/performance")
    class Performance {

        @Test
        @DisplayName("指定 days=7 → 正確傳遞給 Service")
        void performanceWithCustomDays() {
            PerformanceStats stats = PerformanceStats.builder()
                    .summary(PerformanceStats.Summary.builder()
                            .totalTrades(10).winningTrades(6).losingTrades(4)
                            .winRate(60.0).profitFactor(1.5)
                            .totalNetProfit(500.0).avgProfitPerTrade(50.0)
                            .totalCommission(20.0)
                            .maxWin(200.0).maxLoss(-100.0)
                            .avgWin(100.0).avgLoss(-50.0)
                            .riskRewardRatio(2.0).expectancy(40.0)
                            .maxConsecutiveWins(3).maxConsecutiveLosses(2)
                            .maxDrawdown(-150.0).maxDrawdownPercent(-30.0).maxDrawdownDays(5)
                            .avgHoldingHours(4.5)
                            .build())
                    .exitReasonBreakdown(Map.of("STOP_LOSS", 4L, "SIGNAL_CLOSE", 6L))
                    .signalSourceRanking(List.of())
                    .pnlCurve(List.of())
                    .symbolStats(List.of())
                    .sideComparison(PerformanceStats.SideComparison.builder()
                            .longStats(PerformanceStats.SideStats.builder().trades(5).wins(3).build())
                            .shortStats(PerformanceStats.SideStats.builder().trades(5).wins(3).build())
                            .build())
                    .weeklyStats(List.of())
                    .monthlyStats(List.of())
                    .dayOfWeekStats(List.of())
                    .dcaAnalysis(PerformanceStats.DcaAnalysis.builder().build())
                    .build();

            when(dashboardService.getPerformance("user-123", 7)).thenReturn(stats);

            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");

                ResponseEntity<PerformanceStats> response = controller.getPerformance(7);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getSummary().getTotalTrades()).isEqualTo(10);
                assertThat(response.getBody().getSummary().getWinRate()).isEqualTo(60.0);

                verify(dashboardService).getPerformance("user-123", 7);
            }
        }

        @Test
        @DisplayName("未指定 days → 使用預設值 30")
        void performanceDefaultDays() {
            PerformanceStats stats = PerformanceStats.builder()
                    .summary(PerformanceStats.Summary.builder().totalTrades(0).build())
                    .exitReasonBreakdown(Map.of())
                    .signalSourceRanking(List.of())
                    .pnlCurve(List.of())
                    .symbolStats(List.of())
                    .weeklyStats(List.of())
                    .monthlyStats(List.of())
                    .dayOfWeekStats(List.of())
                    .build();

            when(dashboardService.getPerformance("user-123", 30)).thenReturn(stats);

            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");

                // 使用 @RequestParam 預設值 30
                ResponseEntity<PerformanceStats> response = controller.getPerformance(30);

                verify(dashboardService).getPerformance("user-123", 30);
            }
        }
    }

    // ==================== /trades ====================

    @Nested
    @DisplayName("GET /api/dashboard/trades")
    class TradeHistory {

        @Test
        @DisplayName("指定分頁 page=1, size=10 → 正確傳遞")
        void tradeHistoryWithPagination() {
            TradeHistoryResponse historyResponse = TradeHistoryResponse.builder()
                    .trades(List.of(
                            TradeHistoryResponse.TradeRecord.builder()
                                    .tradeId("trade-1").symbol("BTCUSDT").side("LONG")
                                    .entryPrice(50000.0).exitPrice(51000.0)
                                    .netProfit(100.0).exitReason("SIGNAL_CLOSE")
                                    .status("CLOSED")
                                    .build()
                    ))
                    .pagination(TradeHistoryResponse.Pagination.builder()
                            .page(1).size(10).totalPages(5).totalElements(50)
                            .build())
                    .build();

            when(dashboardService.getTradeHistory("user-123", 1, 10)).thenReturn(historyResponse);

            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");

                ResponseEntity<TradeHistoryResponse> response = controller.getTradeHistory(1, 10);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getTrades()).hasSize(1);
                assertThat(response.getBody().getTrades().get(0).getSymbol()).isEqualTo("BTCUSDT");
                assertThat(response.getBody().getPagination().getTotalElements()).isEqualTo(50);

                verify(dashboardService).getTradeHistory("user-123", 1, 10);
            }
        }

        @Test
        @DisplayName("空交易歷史 → 回傳空列表 + pagination")
        void tradeHistoryEmpty() {
            TradeHistoryResponse emptyResponse = TradeHistoryResponse.builder()
                    .trades(List.of())
                    .pagination(TradeHistoryResponse.Pagination.builder()
                            .page(0).size(20).totalPages(0).totalElements(0)
                            .build())
                    .build();

            when(dashboardService.getTradeHistory("user-123", 0, 20)).thenReturn(emptyResponse);

            try (MockedStatic<SecurityUtil> securityUtil = mockStatic(SecurityUtil.class)) {
                securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");

                ResponseEntity<TradeHistoryResponse> response = controller.getTradeHistory(0, 20);

                assertThat(response.getBody().getTrades()).isEmpty();
                assertThat(response.getBody().getPagination().getTotalElements()).isEqualTo(0);
            }
        }
    }
}
