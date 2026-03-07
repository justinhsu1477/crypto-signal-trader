package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.service.TradeRecordService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdvisorService — per-user Adapter 注入測試
 *
 * 覆蓋場景：
 * 1. 單用戶模式 → 使用預設 Adapter（getDefaultAdapter）
 * 2. 單用戶模式 → context 正常組裝
 * 3. 多用戶模式 → setAdvisoryContext 設定 + clearAdvisoryContext 清除
 * 4. 多用戶模式 → 未設 Adapter 時 fallback 到預設
 */
class AdvisorServicePerUserKeyTest {

    private GeminiService geminiService;
    private ExchangeAdapterFactory exchangeAdapterFactory;
    private ExchangeAdapter defaultAdapter;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService webhookService;
    private AdvisorConfig advisorConfig;
    private RiskConfig riskConfig;
    private MultiUserConfig multiUserConfig;
    private AdvisorService advisorService;

    @BeforeEach
    void setUp() {
        geminiService = mock(GeminiService.class);
        exchangeAdapterFactory = mock(ExchangeAdapterFactory.class);
        defaultAdapter = mock(ExchangeAdapter.class);
        tradeRecordService = mock(TradeRecordService.class);
        webhookService = mock(DiscordWebhookService.class);
        advisorConfig = mock(AdvisorConfig.class);
        riskConfig = mock(RiskConfig.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false

        when(exchangeAdapterFactory.getDefaultAdapter()).thenReturn(defaultAdapter);

        when(advisorConfig.getRecentTradesCount()).thenReturn(10);
        when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);
        when(riskConfig.getRiskPercent()).thenReturn(0.02);
        when(riskConfig.getFixedLeverage()).thenReturn(10);
        when(riskConfig.getMaxDcaPerSymbol()).thenReturn(3);

        advisorService = new AdvisorService(
                geminiService, exchangeAdapterFactory, tradeRecordService,
                webhookService, advisorConfig, riskConfig,
                multiUserConfig);
    }

    @AfterEach
    void tearDown() {
        advisorService.clearAdvisoryContext();
    }

    private void setupDefaultMocks() {
        when(defaultAdapter.getAvailableBalance()).thenReturn(5000.0);
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
        @DisplayName("runAdvisory 使用預設 Adapter（getDefaultAdapter）")
        void usesDefaultAdapter() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("分析完成"));

            advisorService.runAdvisory();

            // 確認正常呼叫了預設 Adapter
            verify(defaultAdapter).getAvailableBalance();
            verify(exchangeAdapterFactory).getDefaultAdapter();
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
            when(defaultAdapter.getAvailableBalance()).thenReturn(5000.0);
            Trade trade = new Trade();
            trade.setSymbol("BTCUSDT");
            trade.setSide("LONG");
            trade.setEntryPrice(50000.0);
            trade.setEntryQuantity(0.01);
            trade.setStopLoss(49000.0);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of(trade));
            when(defaultAdapter.getMarkPrice("BTCUSDT")).thenReturn(51000.0);
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
    @DisplayName("多用戶模式 — setAdvisoryContext / clearAdvisoryContext")
    class MultiUserModeAdapterManagement {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("setAdvisoryContext 設定 per-user Adapter 後，runAdvisory 使用該 Adapter")
        void setsUserAdapter() {
            ExchangeAdapter userAdapter = mock(ExchangeAdapter.class);
            when(userAdapter.getAvailableBalance()).thenReturn(8000.0);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of("totalNetProfit", 0.0, "winRate", "0%", "profitFactor", 0.0));
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.setAdvisoryContext(userAdapter);
            advisorService.runAdvisory();

            // 確認使用的是 per-user adapter，而非預設
            verify(userAdapter).getAvailableBalance();
            verify(defaultAdapter, never()).getAvailableBalance();
        }

        @Test
        @DisplayName("clearAdvisoryContext 清除 per-user Adapter 並呼叫 clearCredentials")
        void clearsUserAdapter() {
            ExchangeAdapter userAdapter = mock(ExchangeAdapter.class);
            advisorService.setAdvisoryContext(userAdapter);
            advisorService.clearAdvisoryContext();

            verify(userAdapter).clearCredentials();
        }

        @Test
        @DisplayName("clearAdvisoryContext 後 fallback 到預設 Adapter")
        void afterClearFallsBackToDefault() {
            ExchangeAdapter userAdapter = mock(ExchangeAdapter.class);
            advisorService.setAdvisoryContext(userAdapter);
            advisorService.clearAdvisoryContext();

            // 設定 default adapter mock
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            // 清除後應使用預設 Adapter
            verify(defaultAdapter).getAvailableBalance();
        }

        @Test
        @DisplayName("未設 Adapter 時 runAdvisory fallback 到預設 Adapter")
        void runAdvisoryFallsBackToDefaultWithoutContext() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            // 多用戶模式下，但未呼叫 setAdvisoryContext，應 fallback 到預設
            assertThatCode(() -> advisorService.runAdvisory()).doesNotThrowAnyException();
            verify(defaultAdapter).getAvailableBalance();
            verify(webhookService).sendNotification(anyString(), eq("ok"), anyInt());
        }
    }
}
