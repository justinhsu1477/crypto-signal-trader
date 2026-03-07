package com.trader.dashboard.service;

import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserDiscordWebhookService;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService.getBatchLightweightUserStats() 測試
 *
 * 重點測試：
 * - consecutiveLosses 計算邏輯（全贏、全輸、混合、0 筆）
 * - lastTradeAt 正確取得最新交易時間
 * - hasBinanceApiKey 批次查詢
 * - circuitBreakerActive 熔斷判斷
 */
class DashboardServiceBatchStatsTest {

    private TradeRecordService tradeRecordService;
    private TradeRepository tradeRepository;
    private UserApiKeyService userApiKeyService;
    private TradeConfigResolver tradeConfigResolver;
    private DashboardService dashboardService;

    /** 建立 Object[] row — 解決 List.of(Object[]) 泛型推斷問題 */
    private static List<Object[]> rows(Object[]... items) {
        return Arrays.asList(items);
    }

    @BeforeEach
    void setUp() {
        tradeRecordService = mock(TradeRecordService.class);
        tradeRepository = mock(TradeRepository.class);
        userApiKeyService = mock(UserApiKeyService.class);
        tradeConfigResolver = mock(TradeConfigResolver.class);

        when(tradeRecordService.getTradeRepository()).thenReturn(tradeRepository);

        // 預設：空結果（各 batch query）
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(List.of());
        when(tradeRepository.aggregateTodayStatsPerUser(any())).thenReturn(List.of());
        when(tradeRepository.aggregateStatsPerUserSince(any())).thenReturn(List.of());
        when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(List.of());
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of());

        dashboardService = new DashboardService(
                tradeRecordService,
                mock(SubscriptionService.class),
                mock(ExchangeAdapterFactory.class),
                mock(RiskConfig.class),
                mock(UserRepository.class),
                tradeConfigResolver,
                mock(MultiUserConfig.class),
                userApiKeyService,
                mock(UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                tradeRepository,
                mock(com.trader.referral.repository.UserExchangeReferralLinkRepository.class),
                mock(com.trader.subscription.repository.SubscriptionRepository.class)
        );
    }

    // ── consecutiveLosses ──

    @Nested
    @DisplayName("consecutiveLosses 計算")
    class ConsecutiveLossesTests {

        @Test
        @DisplayName("0 筆交易 → consecutiveLosses = 0")
        void noTrades() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 0L, 0L, 0.0, 0L}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(0);
        }

        @Test
        @DisplayName("全部虧損 → consecutiveLosses = 交易筆數")
        void allLosses() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 3L, 0L, -300.0, 0L}
            ));
            LocalDateTime now = LocalDateTime.now();
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", now, -100.0},
                    new Object[]{"u1", now.minusHours(1), -80.0},
                    new Object[]{"u1", now.minusHours(2), -120.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(3);
        }

        @Test
        @DisplayName("全部獲利 → consecutiveLosses = 0")
        void allWins() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 3L, 3L, 300.0, 0L}
            ));
            LocalDateTime now = LocalDateTime.now();
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", now, 100.0},
                    new Object[]{"u1", now.minusHours(1), 80.0},
                    new Object[]{"u1", now.minusHours(2), 120.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(0);
        }

        @Test
        @DisplayName("混合：最近 2 筆虧損、第 3 筆獲利 → consecutiveLosses = 2")
        void mixedPattern() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 5L, 2L, -50.0, 0L}
            ));
            LocalDateTime now = LocalDateTime.now();
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", now, -30.0},
                    new Object[]{"u1", now.minusHours(1), -20.0},
                    new Object[]{"u1", now.minusHours(2), 50.0},
                    new Object[]{"u1", now.minusHours(3), -10.0},
                    new Object[]{"u1", now.minusHours(4), 60.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(2);
        }

        @Test
        @DisplayName("多用戶各自獨立計算")
        void multipleUsersIndependent() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 3L, 1L, -50.0, 0L},
                    new Object[]{"u2", 2L, 2L, 100.0, 0L}
            ));
            LocalDateTime now = LocalDateTime.now();
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", now, -30.0},
                    new Object[]{"u1", now.minusHours(1), -20.0},
                    new Object[]{"u1", now.minusHours(2), 50.0},
                    new Object[]{"u2", now, 80.0},
                    new Object[]{"u2", now.minusHours(1), 20.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(2);
            assertThat(result.get("u2").get("consecutiveLosses")).isEqualTo(0);
        }

        @Test
        @DisplayName("netProfit = 0 不算虧損 → 中斷連續虧損計算")
        void zeroNetProfitBreaksStreak() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 3L, 0L, -50.0, 0L}
            ));
            LocalDateTime now = LocalDateTime.now();
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", now, -30.0},
                    new Object[]{"u1", now.minusHours(1), 0.0},
                    new Object[]{"u1", now.minusHours(2), -20.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("consecutiveLosses")).isEqualTo(1);
        }
    }

    // ── lastTradeAt ──

    @Nested
    @DisplayName("lastTradeAt 計算")
    class LastTradeAtTests {

        @Test
        @DisplayName("有交易 → lastTradeAt = 最新 exitTime")
        void hasTradesReturnsLatest() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 2L, 1L, 50.0, 0L}
            ));
            LocalDateTime latest = LocalDateTime.of(2025, 3, 1, 10, 0);
            when(tradeRepository.findRecentClosedTradesAllUsers()).thenReturn(rows(
                    new Object[]{"u1", latest, 30.0},
                    new Object[]{"u1", latest.minusDays(1), 20.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("lastTradeAt")).isEqualTo(latest);
        }

        @Test
        @DisplayName("無已平倉交易 → lastTradeAt = null")
        void noClosedTrades() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 0L, 0L, 0.0, 1L}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("lastTradeAt")).isNull();
        }
    }

    // ── hasBinanceApiKey ──

    @Nested
    @DisplayName("hasBinanceApiKey 批次查詢")
    class ApiKeyTests {

        @Test
        @DisplayName("有 API Key → true、無 → false")
        void apiKeyPresence() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 1L, 0L, 0.0, 0L},
                    new Object[]{"u2", 1L, 0L, 0.0, 0L}
            ));
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u1"));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("hasBinanceApiKey")).isEqualTo(true);
            assertThat(result.get("u2").get("hasBinanceApiKey")).isEqualTo(false);
        }
    }

    // ── circuitBreakerActive ──

    @Nested
    @DisplayName("circuitBreakerActive 熔斷判斷")
    class CircuitBreakerTests {

        @Test
        @DisplayName("今日虧損超過 maxDailyLossUsdt → 熔斷 active")
        void lossExceedsLimit() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 5L, 2L, -200.0, 0L}
            ));
            when(tradeRepository.aggregateTodayStatsPerUser(any())).thenReturn(rows(
                    new Object[]{"u1", 3L, -150.0}
            ));
            EffectiveTradeConfig config = mock(EffectiveTradeConfig.class);
            when(config.maxDailyLossUsdt()).thenReturn(100.0);
            when(tradeConfigResolver.resolve("u1")).thenReturn(config);

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("circuitBreakerActive")).isEqualTo(true);
        }

        @Test
        @DisplayName("今日虧損未達上限 → 熔斷 inactive")
        void lossBelowLimit() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 5L, 2L, -50.0, 0L}
            ));
            when(tradeRepository.aggregateTodayStatsPerUser(any())).thenReturn(rows(
                    new Object[]{"u1", 1L, -30.0}
            ));
            EffectiveTradeConfig config = mock(EffectiveTradeConfig.class);
            when(config.maxDailyLossUsdt()).thenReturn(100.0);
            when(tradeConfigResolver.resolve("u1")).thenReturn(config);

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("circuitBreakerActive")).isEqualTo(false);
        }

        @Test
        @DisplayName("今日獲利 → 熔斷 inactive（不管上限）")
        void profitableToday() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 5L, 4L, 200.0, 0L}
            ));
            when(tradeRepository.aggregateTodayStatsPerUser(any())).thenReturn(rows(
                    new Object[]{"u1", 2L, 80.0}
            ));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("circuitBreakerActive")).isEqualTo(false);
        }

        @Test
        @DisplayName("config 解析失敗 → 忽略，熔斷 inactive")
        void configResolutionFails() {
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(rows(
                    new Object[]{"u1", 5L, 2L, -200.0, 0L}
            ));
            when(tradeRepository.aggregateTodayStatsPerUser(any())).thenReturn(rows(
                    new Object[]{"u1", 3L, -150.0}
            ));
            when(tradeConfigResolver.resolve("u1")).thenThrow(new RuntimeException("config not found"));

            Map<String, Map<String, Object>> result = dashboardService.getBatchLightweightUserStats();

            assertThat(result.get("u1").get("circuitBreakerActive")).isEqualTo(false);
        }
    }
}
