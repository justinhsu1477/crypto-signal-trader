package com.trader.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * DashboardService — Overview 端點測試
 *
 * 測試 getOverview() 方法的：
 * - AccountSummary（餘額、持倉、今日盈虧）
 * - RiskBudget（日虧損熔斷機制）
 * - SubscriptionInfo（訂閱狀態）
 * - OpenPositionSummary（持倉列表）
 * - 各種異常/降級處理
 */
class DashboardServiceOverviewTest {

    private DashboardService dashboardService;
    private TradeRecordService tradeRecordService;
    private SubscriptionService subscriptionService;
    private BinanceFuturesService binanceFuturesService;
    private RiskConfig riskConfig;
    private TradeConfigResolver tradeConfigResolver;

    @BeforeEach
    void setUp() {
        tradeRecordService = Mockito.mock(TradeRecordService.class);
        subscriptionService = Mockito.mock(SubscriptionService.class);
        binanceFuturesService = Mockito.mock(BinanceFuturesService.class);
        riskConfig = Mockito.mock(RiskConfig.class);
        tradeConfigResolver = Mockito.mock(TradeConfigResolver.class);

        // mock TradeConfigResolver — buildRiskBudget 會用到 resolve().maxDailyLossUsdt()
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                java.util.List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        Mockito.when(tradeConfigResolver.resolve(Mockito.any())).thenReturn(defaultConfig);

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService, riskConfig, Mockito.mock(UserRepository.class),
                tradeConfigResolver);
    }

    // ==================== AccountSummary ====================

    @Nested
    @DisplayName("AccountSummary 帳戶概況")
    class AccountSummaryTest {

        @Test
        @DisplayName("正常情境 — 有餘額、有持倉、有今日盈虧")
        void normalScenario() {
            // 設定餘額
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);

            // 設定今日統計
            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 3L);
            todayStats.put("netProfit", 150.75);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);

            // 設定 OPEN trades
            List<Trade> openTrades = List.of(
                    Trade.builder().symbol("BTCUSDT").side("LONG")
                            .entryPrice(50000.0).stopLoss(49000.0)
                            .riskAmount(400.0).dcaCount(0)
                            .sourceAuthorName("陳哥").entryTime(LocalDateTime.now())
                            .build(),
                    Trade.builder().symbol("ETHUSDT").side("SHORT")
                            .entryPrice(3000.0).stopLoss(3100.0)
                            .riskAmount(200.0).dcaCount(1)
                            .sourceAuthorName("老王").entryTime(LocalDateTime.now())
                            .build()
            );
            when(tradeRecordService.findAllOpenTrades()).thenReturn(openTrades);

            // 設定風控
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(-300.0);

            // 設定訂閱
            when(subscriptionService.getStatus("user-1")).thenReturn(
                    SubscriptionStatusResponse.builder()
                            .planId("pro").status("ACTIVE").active(true)
                            .currentPeriodEnd(LocalDateTime.of(2026, 12, 31, 23, 59))
                            .build());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            // 驗證 AccountSummary
            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(5000.0);
            assertThat(overview.getAccount().getOpenPositionCount()).isEqualTo(2);
            assertThat(overview.getAccount().getTodayPnl()).isCloseTo(150.75, within(0.01));
            assertThat(overview.getAccount().getTodayTradeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Binance API 失敗 → 餘額降級為 0")
        void binanceApiFailed() {
            when(binanceFuturesService.getAvailableBalance())
                    .thenThrow(new RuntimeException("API timeout"));

            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 0L);
            todayStats.put("netProfit", 0.0);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(subscriptionService.getStatus("user-1")).thenReturn(
                    SubscriptionStatusResponse.builder().status("NONE").active(false).build());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            // 餘額應降級為 0，不應拋出異常
            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("無持倉、無交易 → 帳戶概況為空值/零")
        void emptyAccount() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);

            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 0L);
            todayStats.put("netProfit", 0.0);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(subscriptionService.getStatus("user-1")).thenReturn(
                    SubscriptionStatusResponse.builder().status("NONE").active(false).build());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getAccount().getOpenPositionCount()).isEqualTo(0);
            assertThat(overview.getAccount().getTodayPnl()).isEqualTo(0.0);
            assertThat(overview.getAccount().getTodayTradeCount()).isEqualTo(0);
        }
    }

    // ==================== RiskBudget ====================

    @Nested
    @DisplayName("RiskBudget 風控預算")
    class RiskBudgetTest {

        private void setupBasicMocks() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 0L);
            todayStats.put("netProfit", 0.0);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(subscriptionService.getStatus(anyString())).thenReturn(
                    SubscriptionStatusResponse.builder().status("NONE").active(false).build());
        }

        @Test
        @DisplayName("今日虧損 300 / 上限 2000 → 剩餘 1700，未觸發熔斷")
        void riskBudgetNormal() {
            setupBasicMocks();
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(-300.0);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getRiskBudget().getDailyLossLimit()).isEqualTo(2000.0);
            assertThat(overview.getRiskBudget().getTodayLossUsed()).isEqualTo(300.0);
            assertThat(overview.getRiskBudget().getRemainingBudget()).isEqualTo(1700.0);
            assertThat(overview.getRiskBudget().isCircuitBreakerActive()).isFalse();
        }

        @Test
        @DisplayName("今日虧損 2000 = 上限 → 熔斷觸發，剩餘 0")
        void riskBudgetExact() {
            setupBasicMocks();
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(-2000.0);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getRiskBudget().getTodayLossUsed()).isEqualTo(2000.0);
            assertThat(overview.getRiskBudget().getRemainingBudget()).isEqualTo(0.0);
            assertThat(overview.getRiskBudget().isCircuitBreakerActive()).isTrue();
        }

        @Test
        @DisplayName("今日虧損超過上限 → 熔斷觸發，剩餘 0（不為負）")
        void riskBudgetExceeded() {
            setupBasicMocks();
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(-2500.0);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getRiskBudget().getTodayLossUsed()).isEqualTo(2500.0);
            assertThat(overview.getRiskBudget().getRemainingBudget()).isEqualTo(0.0);
            assertThat(overview.getRiskBudget().isCircuitBreakerActive()).isTrue();
        }

        @Test
        @DisplayName("今日無虧損（todayLoss = 0） → 剩餘 = 上限")
        void riskBudgetNoLoss() {
            setupBasicMocks();
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getRiskBudget().getTodayLossUsed()).isEqualTo(0.0);
            assertThat(overview.getRiskBudget().getRemainingBudget()).isEqualTo(2000.0);
            assertThat(overview.getRiskBudget().isCircuitBreakerActive()).isFalse();
        }
    }

    // ==================== SubscriptionInfo ====================

    @Nested
    @DisplayName("SubscriptionInfo 訂閱狀態")
    class SubscriptionInfoTest {

        private void setupBasicMocks() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 0L);
            todayStats.put("netProfit", 0.0);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
        }

        @Test
        @DisplayName("有效訂閱 → active=true + plan + expiresAt")
        void activeSubscription() {
            setupBasicMocks();
            LocalDateTime expiresAt = LocalDateTime.of(2026, 12, 31, 23, 59);
            when(subscriptionService.getStatus("user-1")).thenReturn(
                    SubscriptionStatusResponse.builder()
                            .planId("pro").status("ACTIVE").active(true)
                            .currentPeriodEnd(expiresAt)
                            .build());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getSubscription().getPlan()).isEqualTo("pro");
            assertThat(overview.getSubscription().isActive()).isTrue();
            assertThat(overview.getSubscription().getExpiresAt()).isEqualTo(expiresAt.toString());
        }

        @Test
        @DisplayName("無訂閱 → plan=none, active=false")
        void noSubscription() {
            setupBasicMocks();
            when(subscriptionService.getStatus("user-1")).thenReturn(
                    SubscriptionStatusResponse.builder()
                            .status("NONE").active(false)
                            .build());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getSubscription().getPlan()).isEqualTo("none");
            assertThat(overview.getSubscription().isActive()).isFalse();
            assertThat(overview.getSubscription().getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("訂閱服務拋出異常 → 降級為 plan=none")
        void subscriptionServiceFails() {
            setupBasicMocks();
            when(subscriptionService.getStatus("user-1"))
                    .thenThrow(new RuntimeException("Stripe API error"));

            DashboardOverview overview = dashboardService.getOverview("user-1");

            // 不應拋出異常，應降級
            assertThat(overview.getSubscription().getPlan()).isEqualTo("none");
            assertThat(overview.getSubscription().isActive()).isFalse();
        }
    }

    // ==================== OpenPositionSummary ====================

    @Nested
    @DisplayName("OpenPositionSummary 持倉列表")
    class OpenPositionSummaryTest {

        private void setupBasicMocksExceptOpenTrades() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
            Map<String, Object> todayStats = new LinkedHashMap<>();
            todayStats.put("trades", 0L);
            todayStats.put("netProfit", 0.0);
            when(tradeRecordService.getTodayStats()).thenReturn(todayStats);
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(subscriptionService.getStatus(anyString())).thenReturn(
                    SubscriptionStatusResponse.builder().status("NONE").active(false).build());
        }

        @Test
        @DisplayName("多筆持倉 → 正確映射所有欄位")
        void multiplePositions() {
            setupBasicMocksExceptOpenTrades();

            LocalDateTime entryTime = LocalDateTime.of(2026, 1, 15, 14, 30);
            List<Trade> openTrades = List.of(
                    Trade.builder()
                            .symbol("BTCUSDT").side("LONG")
                            .entryPrice(50000.0).stopLoss(49000.0)
                            .riskAmount(400.0).dcaCount(0)
                            .sourceAuthorName("陳哥")
                            .entryTime(entryTime)
                            .build(),
                    Trade.builder()
                            .symbol("ETHUSDT").side("SHORT")
                            .entryPrice(3000.0).stopLoss(3100.0)
                            .riskAmount(200.0).dcaCount(2)
                            .sourceAuthorName("老王")
                            .entryTime(entryTime.plusHours(1))
                            .build()
            );
            when(tradeRecordService.findAllOpenTrades()).thenReturn(openTrades);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getPositions()).hasSize(2);

            DashboardOverview.OpenPositionSummary pos1 = overview.getPositions().get(0);
            assertThat(pos1.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(pos1.getSide()).isEqualTo("LONG");
            assertThat(pos1.getEntryPrice()).isEqualTo(50000.0);
            assertThat(pos1.getStopLoss()).isEqualTo(49000.0);
            assertThat(pos1.getRiskAmount()).isEqualTo(400.0);
            assertThat(pos1.getDcaCount()).isEqualTo(0);
            assertThat(pos1.getSignalSource()).isEqualTo("陳哥");

            DashboardOverview.OpenPositionSummary pos2 = overview.getPositions().get(1);
            assertThat(pos2.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(pos2.getDcaCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("無持倉 → 空列表")
        void noPositions() {
            setupBasicMocksExceptOpenTrades();
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getPositions()).isEmpty();
        }

        @Test
        @DisplayName("entryPrice 為 null → 使用 0 作為預設值")
        void nullEntryPrice() {
            setupBasicMocksExceptOpenTrades();

            List<Trade> openTrades = List.of(
                    Trade.builder()
                            .symbol("BTCUSDT").side("LONG")
                            .entryPrice(null).stopLoss(49000.0)
                            .entryTime(LocalDateTime.now())
                            .build()
            );
            when(tradeRecordService.findAllOpenTrades()).thenReturn(openTrades);

            DashboardOverview overview = dashboardService.getOverview("user-1");

            assertThat(overview.getPositions().get(0).getEntryPrice()).isEqualTo(0.0);
        }
    }
}
