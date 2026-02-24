package com.trader.dashboard.service;

import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService — per-user API Key 注入測試
 *
 * 覆蓋場景：
 * 1. 單用戶模式 → 直接使用全局 API Key（不呼叫 setCurrentUserKeys）
 * 2. 多用戶模式 + 有 API Key → 設定 per-user Key 並在 finally 清除
 * 3. 多用戶模式 + 無 API Key → 餘額返回 0，不呼叫 Binance
 * 4. Binance API 例外 → 餘額返回 0，不崩潰
 */
class DashboardServicePerUserKeyTest {

    private TradeRecordService tradeRecordService;
    private SubscriptionService subscriptionService;
    private BinanceFuturesService binanceFuturesService;
    private RiskConfig riskConfig;
    private UserRepository userRepository;
    private TradeConfigResolver tradeConfigResolver;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private DashboardService dashboardService;

    private static final String USER_ID = "testUser";

    @BeforeEach
    void setUp() {
        tradeRecordService = mock(TradeRecordService.class);
        subscriptionService = mock(SubscriptionService.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        riskConfig = mock(RiskConfig.class);
        userRepository = mock(UserRepository.class);
        tradeConfigResolver = mock(TradeConfigResolver.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false
        userApiKeyService = mock(UserApiKeyService.class);

        // Dashboard 基本 mock（讓 getOverview 不崩潰）
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tradeRecordService.getTodayStats(USER_ID))
                .thenReturn(Map.of("trades", 0L, "netProfit", 0.0));
        when(tradeRecordService.findAllOpenTrades(USER_ID)).thenReturn(List.of());
        when(tradeRecordService.getTodayRealizedLoss(USER_ID)).thenReturn(0.0);
        var mockConfig = mock(com.trader.trading.dto.EffectiveTradeConfig.class);
        when(mockConfig.maxDailyLossUsdt()).thenReturn(2000.0);
        when(tradeConfigResolver.resolve(USER_ID)).thenReturn(mockConfig);
        when(subscriptionService.getStatus(USER_ID))
                .thenThrow(new RuntimeException("no subscription"));

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService,
                riskConfig, userRepository, tradeConfigResolver,
                multiUserConfig, userApiKeyService);
    }

    @Nested
    @DisplayName("單用戶模式（multiUser.enabled=false）")
    class SingleUserMode {

        @Test
        @DisplayName("直接使用全局 API Key — 不呼叫 setCurrentUserKeys")
        void usesGlobalApiKey() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(5000.0);

            // 確認不呼叫 per-user key 相關方法
            verify(userApiKeyService, never()).getUserBinanceKeys(anyString());
        }

        @Test
        @DisplayName("Binance API 失敗 — 餘額為 0，不崩潰")
        void balanceFailureReturnsZero() {
            when(binanceFuturesService.getAvailableBalance()).thenThrow(new RuntimeException("timeout"));

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("多用戶模式（multiUser.enabled=true）")
    class MultiUserMode {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("有 API Key → 設定 per-user Key → 查詢餘額 → finally 清除")
        void setsAndClearsUserKeys() {
            BinanceKeys userKeys = new BinanceKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserBinanceKeys(USER_ID)).thenReturn(Optional.of(userKeys));
            when(binanceFuturesService.getAvailableBalance()).thenReturn(8888.0);

            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                var overview = dashboardService.getOverview(USER_ID);

                assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(8888.0);

                // 驗證 per-user key 注入和清除
                bfsMock.verify(() -> BinanceFuturesService.setCurrentUserKeys(userKeys));
                bfsMock.verify(() -> BinanceFuturesService.clearCurrentUserKeys());
            }
        }

        @Test
        @DisplayName("無 API Key → 餘額返回 0，不呼叫 Binance API")
        void noApiKeyReturnsZeroBalance() {
            when(userApiKeyService.getUserBinanceKeys(USER_ID)).thenReturn(Optional.empty());

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);
            // 不應呼叫 Binance API
            verify(binanceFuturesService, never()).getAvailableBalance();
        }

        @Test
        @DisplayName("有 API Key 但 Binance 拋例外 → 餘額為 0，Key 仍被清除")
        void apiExceptionStillClearsKeys() {
            BinanceKeys userKeys = new BinanceKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserBinanceKeys(USER_ID)).thenReturn(Optional.of(userKeys));
            when(binanceFuturesService.getAvailableBalance()).thenThrow(new RuntimeException("Binance error"));

            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                var overview = dashboardService.getOverview(USER_ID);

                assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);

                // 即使 API 失敗，仍應清除 key
                bfsMock.verify(() -> BinanceFuturesService.setCurrentUserKeys(userKeys));
                bfsMock.verify(() -> BinanceFuturesService.clearCurrentUserKeys());
            }
        }
    }
}
