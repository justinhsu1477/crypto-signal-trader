package com.trader.dashboard.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService — 資產配置相關測試
 *
 * 覆蓋場景：
 * 1. Binance 即時數據 enrichment（markPrice, unrealizedPnl, marginUsed）
 * 2. positionValue 計算（entryPrice × entryQuantity）
 * 3. Binance API 失敗 — graceful degradation（即時欄位為 null）
 * 4. 保證金統計（totalMarginUsed, marginRatio）
 * 5. AI 欄位映射到 OpenPositionSummary
 * 6. 多用戶模式下 per-user key 注入
 */
class DashboardServicePortfolioTest {

    private TradeRecordService tradeRecordService;
    private SubscriptionService subscriptionService;
    private BinanceFuturesService binanceFuturesService;
    private TradeConfigResolver tradeConfigResolver;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private UserRepository userRepository;
    private DashboardService dashboardService;

    private static final String USER_ID = "testUser";

    @BeforeEach
    void setUp() {
        tradeRecordService = mock(TradeRecordService.class);
        subscriptionService = mock(SubscriptionService.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        tradeConfigResolver = mock(TradeConfigResolver.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false
        userApiKeyService = mock(UserApiKeyService.class);
        userRepository = mock(UserRepository.class);

        // Dashboard 基本 mock（讓 getOverview 不崩潰）
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tradeRecordService.getTodayStats(USER_ID))
                .thenReturn(Map.of("trades", 0L, "netProfit", 0.0));
        when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of());
        when(tradeRecordService.getTodayRealizedLoss(USER_ID)).thenReturn(0.0);
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of());

        var mockConfig = mock(com.trader.trading.dto.EffectiveTradeConfig.class);
        when(mockConfig.maxDailyLossUsdt()).thenReturn(2000.0);
        when(tradeConfigResolver.resolve(USER_ID)).thenReturn(mockConfig);
        when(subscriptionService.getStatus(USER_ID))
                .thenThrow(new RuntimeException("no subscription"));
        when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService,
                mock(RiskConfig.class), userRepository, tradeConfigResolver,
                multiUserConfig, userApiKeyService,
                mock(com.trader.user.service.UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                mock(com.trader.trading.repository.TradeRepository.class),
                mock(com.trader.referral.repository.UserExchangeReferralLinkRepository.class),
                mock(com.trader.subscription.repository.SubscriptionRepository.class));
    }

    /** 建立帶有 AI 欄位的 open Trade */
    private Trade buildOpenTrade(String symbol, String side, double entryPrice,
                                 Double entryQuantity, Double stopLoss,
                                 Integer aiConfidence, String aiReasoning) {
        Trade t = new Trade();
        t.setSymbol(symbol);
        t.setSide(side);
        t.setEntryPrice(entryPrice);
        t.setEntryQuantity(entryQuantity);
        t.setStopLoss(stopLoss);
        t.setStatus("OPEN");
        t.setEntryTime(LocalDateTime.of(2024, 1, 1, 10, 0));
        t.setAiConfidence(aiConfidence);
        t.setAiReasoning(aiReasoning);
        return t;
    }

    /** 模擬 Binance /fapi/v2/positionRisk 回傳 JSON */
    private String buildBinancePositionJson(String symbol, double markPrice,
                                             double unrealizedPnl, double isolatedMargin,
                                             double positionAmt) {
        return String.format(
                "[{\"symbol\":\"%s\",\"markPrice\":\"%.2f\",\"unRealizedProfit\":\"%.4f\"," +
                "\"isolatedMargin\":\"%.4f\",\"positionAmt\":\"%.4f\"}]",
                symbol, markPrice, unrealizedPnl, isolatedMargin, positionAmt);
    }

    @Nested
    @DisplayName("Binance 即時數據 enrichment")
    class LiveDataEnrichment {

        @Test
        @DisplayName("有 Binance 數據 — markPrice, unrealizedPnl, marginUsed 正確填充")
        void enrichesWithBinanceData() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, 49000.0, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));

            String json = buildBinancePositionJson("BTCUSDT", 51000.0, 10.0, 25.0, 0.01);
            when(binanceFuturesService.getPositions()).thenReturn(json);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getPositions()).hasSize(1);
            var pos = overview.getPositions().get(0);
            assertThat(pos.getMarkPrice()).isEqualTo(51000.0);
            assertThat(pos.getUnrealizedPnl()).isEqualTo(10.0);
            assertThat(pos.getMarginUsed()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("positionValue 計算 — entryPrice × entryQuantity")
        void calculatesPositionValue() {
            Trade trade = buildOpenTrade("ETHUSDT", "SHORT", 3000.0, 0.5, 3100.0, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenReturn("[]");

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getPositionValue()).isEqualTo(1500.0);  // 3000 × 0.5
        }

        @Test
        @DisplayName("entryQuantity 為 null — positionValue 為 null")
        void positionValueNullWhenNoQuantity() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, null, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenReturn("[]");

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getPositionValue()).isNull();
        }

        @Test
        @DisplayName("多筆持倉 — 各自匹配 Binance 數據")
        void multiplePositionsEnriched() {
            Trade btc = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, 49000.0, null, null);
            Trade eth = buildOpenTrade("ETHUSDT", "SHORT", 3000.0, 0.5, 3100.0, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(btc, eth));

            String json = "[" +
                    "{\"symbol\":\"BTCUSDT\",\"markPrice\":\"51000.00\",\"unRealizedProfit\":\"10.0000\"," +
                    "\"isolatedMargin\":\"25.0000\",\"positionAmt\":\"0.0100\"}," +
                    "{\"symbol\":\"ETHUSDT\",\"markPrice\":\"2900.00\",\"unRealizedProfit\":\"50.0000\"," +
                    "\"isolatedMargin\":\"75.0000\",\"positionAmt\":\"-0.5000\"}" +
                    "]";
            when(binanceFuturesService.getPositions()).thenReturn(json);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getPositions()).hasSize(2);
            var btcPos = overview.getPositions().stream()
                    .filter(p -> "BTCUSDT".equals(p.getSymbol())).findFirst().orElseThrow();
            var ethPos = overview.getPositions().stream()
                    .filter(p -> "ETHUSDT".equals(p.getSymbol())).findFirst().orElseThrow();

            assertThat(btcPos.getMarkPrice()).isEqualTo(51000.0);
            assertThat(ethPos.getMarkPrice()).isEqualTo(2900.0);
            assertThat(ethPos.getUnrealizedPnl()).isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("Graceful Degradation")
    class GracefulDegradation {

        @Test
        @DisplayName("Binance getPositions() 拋例外 — 即時欄位為 null，持倉仍有 DB 數據")
        void binanceFailureReturnsNullLiveFields() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, 49000.0, 80, "趨勢明確");
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenThrow(new RuntimeException("Binance timeout"));

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getPositions()).hasSize(1);
            var pos = overview.getPositions().get(0);
            // DB 欄位仍有值
            assertThat(pos.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(pos.getEntryPrice()).isEqualTo(50000.0);
            assertThat(pos.getAiConfidence()).isEqualTo(80);
            // 即時欄位為 null
            assertThat(pos.getMarkPrice()).isNull();
            assertThat(pos.getUnrealizedPnl()).isNull();
            assertThat(pos.getMarginUsed()).isNull();
            // positionValue 仍可計算（來自 DB）
            assertThat(pos.getPositionValue()).isEqualTo(500.0);  // 50000 × 0.01
        }

        @Test
        @DisplayName("Binance 回傳空字串 — 即時欄位為 null")
        void emptyJsonReturnsNullLiveFields() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenReturn("");

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getMarkPrice()).isNull();
            assertThat(pos.getUnrealizedPnl()).isNull();
        }

        @Test
        @DisplayName("Binance 回傳 null — 即時欄位為 null")
        void nullJsonReturnsNullLiveFields() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenReturn(null);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getMarkPrice()).isNull();
        }
    }

    @Nested
    @DisplayName("保證金統計")
    class MarginStats {

        @Test
        @DisplayName("有持倉 — totalMarginUsed 和 marginRatio 正確計算")
        void calculatesMarginStats() {
            Trade btc = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            Trade eth = buildOpenTrade("ETHUSDT", "SHORT", 3000.0, 0.5, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(btc, eth));

            String json = "[" +
                    "{\"symbol\":\"BTCUSDT\",\"markPrice\":\"51000\",\"unRealizedProfit\":\"10\"," +
                    "\"isolatedMargin\":\"250.00\",\"positionAmt\":\"0.01\"}," +
                    "{\"symbol\":\"ETHUSDT\",\"markPrice\":\"2900\",\"unRealizedProfit\":\"50\"," +
                    "\"isolatedMargin\":\"750.00\",\"positionAmt\":\"-0.5\"}" +
                    "]";
            when(binanceFuturesService.getPositions()).thenReturn(json);
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getTotalMarginUsed()).isEqualTo(1000.0);  // 250 + 750
            assertThat(overview.getAccount().getMarginRatio()).isEqualTo(10.0);  // 1000/10000*100
        }

        @Test
        @DisplayName("無持倉 — totalMarginUsed 為 0，marginRatio 為 0")
        void noPositionsZeroMargin() {
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of());
            when(binanceFuturesService.getPositions()).thenReturn("[]");
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getTotalMarginUsed()).isEqualTo(0.0);
            assertThat(overview.getAccount().getMarginRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("balance 為 0 — marginRatio 為 0（避免除以零）")
        void zeroBalanceZeroMarginRatio() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));

            String json = buildBinancePositionJson("BTCUSDT", 51000.0, 10.0, 100.0, 0.01);
            when(binanceFuturesService.getPositions()).thenReturn(json);
            when(binanceFuturesService.getAvailableBalance()).thenReturn(0.0);

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getTotalMarginUsed()).isEqualTo(100.0);
            assertThat(overview.getAccount().getMarginRatio()).isEqualTo(0.0);  // 除以零保護
        }

        @Test
        @DisplayName("Binance 即時數據失敗 — marginUsed 全為 null，totalMarginUsed 為 0")
        void binanceFailureZeroMargin() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenThrow(new RuntimeException("timeout"));

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getTotalMarginUsed()).isEqualTo(0.0);
            assertThat(overview.getAccount().getMarginRatio()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("AI 欄位映射到 OpenPositionSummary")
    class AiFieldsInPositions {

        @Test
        @DisplayName("AI 欄位有值 — aiConfidence + aiReasoning 正確映射")
        void mapsAiFieldsToPosition() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, 49000.0,
                    92, "均線多排，成交量放大");
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(binanceFuturesService.getPositions()).thenReturn("[]");

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getAiConfidence()).isEqualTo(92);
            assertThat(pos.getAiReasoning()).isEqualTo("均線多排，成交量放大");
        }
    }

    @Nested
    @DisplayName("多用戶模式 per-user key")
    class MultiUserLivePositions {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("有 API Key — 設定 per-user key 查詢即時持倉")
        void setsUserKeysForPositions() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));

            BinanceKeys userKeys = new BinanceKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserBinanceKeys(USER_ID)).thenReturn(Optional.of(userKeys));

            String json = buildBinancePositionJson("BTCUSDT", 51000.0, 10.0, 25.0, 0.01);
            when(binanceFuturesService.getPositions()).thenReturn(json);
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);

            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                DashboardOverview overview = dashboardService.getOverview(USER_ID);

                assertThat(overview.getPositions().get(0).getMarkPrice()).isEqualTo(51000.0);

                // 驗證 per-user key 注入（balance + positions 各一次）
                bfsMock.verify(() -> BinanceFuturesService.setCurrentUserKeys(userKeys), atLeast(1));
                bfsMock.verify(() -> BinanceFuturesService.clearCurrentUserKeys(), atLeast(1));
            }
        }

        @Test
        @DisplayName("無 API Key — 即時持倉為空，不呼叫 Binance getPositions()")
        void noApiKeySkipsPositionFetch() {
            Trade trade = buildOpenTrade("BTCUSDT", "LONG", 50000.0, 0.01, null, null, null);
            when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of(trade));
            when(userApiKeyService.getUserBinanceKeys(USER_ID)).thenReturn(Optional.empty());

            DashboardOverview overview = dashboardService.getOverview(USER_ID);

            var pos = overview.getPositions().get(0);
            assertThat(pos.getMarkPrice()).isNull();
            assertThat(pos.getMarginUsed()).isNull();
            // 不應呼叫 getPositions()
            verify(binanceFuturesService, never()).getPositions();
        }
    }
}
