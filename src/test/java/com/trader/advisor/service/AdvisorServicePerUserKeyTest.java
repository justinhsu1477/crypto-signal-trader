package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdvisorService — per-user API Key 注入測試
 *
 * 覆蓋場景：
 * 1. 單用戶模式 → 直接使用全局 API Key（與舊行為一致）
 * 2. 單用戶模式 → context 正常組裝
 * 3. 多用戶模式 → setAdvisoryUserKeys 設定 + clearAdvisoryUserKeys 清除
 * 4. 多用戶模式 → 未設 API Key 時 fallback 到全局
 */
class AdvisorServicePerUserKeyTest {

    private GeminiService geminiService;
    private BinanceFuturesService binanceFuturesService;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService webhookService;
    private AdvisorConfig advisorConfig;
    private RiskConfig riskConfig;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private AdvisorService advisorService;

    @BeforeEach
    void setUp() {
        geminiService = mock(GeminiService.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        tradeRecordService = mock(TradeRecordService.class);
        webhookService = mock(DiscordWebhookService.class);
        advisorConfig = mock(AdvisorConfig.class);
        riskConfig = mock(RiskConfig.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false
        userApiKeyService = mock(UserApiKeyService.class);

        when(advisorConfig.getRecentTradesCount()).thenReturn(10);
        when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
        when(riskConfig.getRiskPercent()).thenReturn(0.02);
        when(riskConfig.getFixedLeverage()).thenReturn(10);
        when(riskConfig.getMaxDcaPerSymbol()).thenReturn(3);

        advisorService = new AdvisorService(
                geminiService, binanceFuturesService, tradeRecordService,
                webhookService, advisorConfig, riskConfig,
                multiUserConfig, userApiKeyService);
    }

    private void setupDefaultMocks() {
        when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
        when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
        when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
        when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
        when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
        when(tradeRecordService.getStatsSummary()).thenReturn(
                Map.of("totalNetProfit", 0.0, "winRate", "0%", "profitFactor", 0.0));
    }

    @Nested
    @DisplayName("單用戶模式（multiUser.enabled=false）")
    class SingleUserMode {

        @Test
        @DisplayName("runAdvisory 使用全局 API Key，不呼叫 userApiKeyService")
        void usesGlobalApiKey() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("分析完成"));

            advisorService.runAdvisory();

            // 確認正常呼叫了 Binance API
            verify(binanceFuturesService).getAvailableBalance();
            // 確認不呼叫 per-user key 相關方法
            verify(userApiKeyService, never()).getUserBinanceKeys(anyString());
        }

        @Test
        @DisplayName("context 包含帳戶餘額")
        void contextIncludesBalance() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), captor.capture());
            assertThat(captor.getValue()).contains("5000.00 USDT");
        }

        @Test
        @DisplayName("有持倉時 context 包含 markPrice")
        void contextIncludesMarkPrice() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
            Trade trade = new Trade();
            trade.setSymbol("BTCUSDT");
            trade.setSide("LONG");
            trade.setEntryPrice(50000.0);
            trade.setEntryQuantity(0.01);
            trade.setStopLoss(49000.0);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of(trade));
            when(binanceFuturesService.getMarkPrice("BTCUSDT")).thenReturn(51000.0);
            when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of());
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), captor.capture());
            assertThat(captor.getValue()).contains("51000.00");
        }
    }

    @Nested
    @DisplayName("多用戶模式 — setAdvisoryUserKeys / clearAdvisoryUserKeys")
    class MultiUserModeKeyManagement {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("setAdvisoryUserKeys 設定 per-user Key")
        void setsUserKeys() {
            BinanceKeys userKeys = new BinanceKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserBinanceKeys("user1")).thenReturn(Optional.of(userKeys));

            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                advisorService.setAdvisoryUserKeys("user1");

                bfsMock.verify(() -> BinanceFuturesService.setCurrentUserKeys(userKeys));
            }
        }

        @Test
        @DisplayName("clearAdvisoryUserKeys 清除 per-user Key")
        void clearsUserKeys() {
            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                advisorService.clearAdvisoryUserKeys();

                bfsMock.verify(() -> BinanceFuturesService.clearCurrentUserKeys());
            }
        }

        @Test
        @DisplayName("用戶無 API Key → setAdvisoryUserKeys 不呼叫 setCurrentUserKeys")
        void noApiKeyDoesNotSetKeys() {
            when(userApiKeyService.getUserBinanceKeys("user2")).thenReturn(Optional.empty());

            try (MockedStatic<BinanceFuturesService> bfsMock = mockStatic(BinanceFuturesService.class)) {
                advisorService.setAdvisoryUserKeys("user2");

                bfsMock.verify(() -> BinanceFuturesService.setCurrentUserKeys(any()), never());
            }
        }

        @Test
        @DisplayName("單用戶模式下 setAdvisoryUserKeys 是 no-op")
        void singleUserModeSetKeysIsNoop() {
            multiUserConfig.setEnabled(false); // 回到單用戶模式

            advisorService.setAdvisoryUserKeys("user1");

            // 不應查詢 API Key
            verify(userApiKeyService, never()).getUserBinanceKeys(anyString());
        }

        @Test
        @DisplayName("多用戶模式 runAdvisory 仍能正常執行（executeBinanceCall 路徑）")
        void runAdvisoryWorksInMultiUserMode() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            // 多用戶模式下，runAdvisory 內部的 executeBinanceCall 走 multi-user 路徑
            // 但因為 ThreadLocal 未設定，會 fallback 到全局 key
            assertThatCode(() -> advisorService.runAdvisory()).doesNotThrowAnyException();
            verify(webhookService).sendNotification(anyString(), eq("ok"), anyInt());
        }
    }
}
