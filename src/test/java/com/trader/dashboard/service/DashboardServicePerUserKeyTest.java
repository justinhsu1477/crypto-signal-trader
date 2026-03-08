package com.trader.dashboard.service;

import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService — per-user API Key 注入測試
 *
 * 覆蓋場景：
 * 1. 單用戶模式 → 直接使用全局 API Key
 * 2. 多用戶模式 + 有 API Key → 設定 per-user Key 並在 finally 清除
 * 3. 多用戶模式 + 無 API Key → 餘額返回 0，不呼叫 Binance
 * 4. Binance API 例外 → 餘額返回 0，不崩潰
 */
class DashboardServicePerUserKeyTest {

    private TradeRecordService tradeRecordService;
    private SubscriptionService subscriptionService;
    private ExchangeAdapterFactory exchangeAdapterFactory;
    private ExchangeAdapter defaultAdapter;
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
        defaultAdapter = mock(ExchangeAdapter.class);
        exchangeAdapterFactory = mock(ExchangeAdapterFactory.class);
        when(exchangeAdapterFactory.getDefaultAdapter()).thenReturn(defaultAdapter);
        when(exchangeAdapterFactory.getAdapter(anyString())).thenReturn(defaultAdapter);
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
                tradeRecordService, subscriptionService, exchangeAdapterFactory,
                riskConfig, userRepository, tradeConfigResolver,
                multiUserConfig, userApiKeyService,
                mock(com.trader.user.service.UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                mock(com.trader.trading.repository.TradeRepository.class),
                mock(com.trader.referral.repository.UserExchangeReferralLinkRepository.class),
                mock(com.trader.subscription.repository.SubscriptionRepository.class));
    }

    @Nested
    @DisplayName("單用戶模式（multiUser.enabled=false）")
    class SingleUserMode {

        @Test
        @DisplayName("直接使用預設交易所 API Key — 不呼叫 getUserPrimaryExchangeKeys")
        void usesGlobalApiKey() {
            when(defaultAdapter.getAvailableBalance()).thenReturn(5000.0);

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(5000.0);

            // 確認不呼叫 per-user key 相關方法
            verify(userApiKeyService, never()).getUserPrimaryExchangeKeys(anyString());
        }

        @Test
        @DisplayName("交易所 API 失敗 — 餘額為 0，不崩潰")
        void balanceFailureReturnsZero() {
            when(defaultAdapter.getAvailableBalance()).thenThrow(new RuntimeException("timeout"));

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
        @DisplayName("有 API Key → 設定 per-user credentials → 查詢餘額 → finally 清除")
        void setsAndClearsUserKeys() {
            ExchangeKeys userKeys = new ExchangeKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserPrimaryExchangeKeys(USER_ID))
                    .thenReturn(Optional.of(Map.entry("BINANCE", userKeys)));
            when(defaultAdapter.getAvailableBalance()).thenReturn(8888.0);

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(8888.0);

            // 驗證 adapter credentials 注入和清除
            verify(defaultAdapter, atLeast(1)).setCredentials(any(ExchangeCredentials.class));
            verify(defaultAdapter, atLeast(1)).clearCredentials();
        }

        @Test
        @DisplayName("無 API Key → 餘額返回 0，不呼叫交易所 API")
        void noApiKeyReturnsZeroBalance() {
            when(userApiKeyService.getUserPrimaryExchangeKeys(USER_ID)).thenReturn(Optional.empty());

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);
            // 不應呼叫交易所 API
            verify(defaultAdapter, never()).getAvailableBalance();
        }

        @Test
        @DisplayName("有 API Key 但交易所拋例外 → 餘額為 0，credentials 仍被清除")
        void apiExceptionStillClearsKeys() {
            ExchangeKeys userKeys = new ExchangeKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserPrimaryExchangeKeys(USER_ID))
                    .thenReturn(Optional.of(Map.entry("BINANCE", userKeys)));
            when(defaultAdapter.getAvailableBalance()).thenThrow(new RuntimeException("Exchange error"));

            var overview = dashboardService.getOverview(USER_ID);

            assertThat(overview.getAccount().getAvailableBalance()).isEqualTo(0.0);

            // 即使 API 失敗，仍應清除 credentials
            verify(defaultAdapter, atLeast(1)).setCredentials(any(ExchangeCredentials.class));
            verify(defaultAdapter, atLeast(1)).clearCredentials();
        }
    }
}
