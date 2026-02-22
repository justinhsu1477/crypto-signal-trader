package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdvisorService 單元測試
 *
 * 覆蓋：runAdvisory 主流程、context 組裝、Gemini 空回應跳過、Discord 通知、例外容錯
 */
class AdvisorServiceTest {

    private GeminiService geminiService;
    private BinanceFuturesService binanceFuturesService;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService webhookService;
    private AdvisorConfig advisorConfig;
    private RiskConfig riskConfig;
    private AdvisorService advisorService;

    @BeforeEach
    void setUp() {
        geminiService = mock(GeminiService.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        tradeRecordService = mock(TradeRecordService.class);
        webhookService = mock(DiscordWebhookService.class);
        advisorConfig = mock(AdvisorConfig.class);
        riskConfig = mock(RiskConfig.class);

        // 預設 config
        when(advisorConfig.getRecentTradesCount()).thenReturn(10);
        when(riskConfig.getMaxDailyLossUsdt()).thenReturn(100.0);
        when(riskConfig.getRiskPercent()).thenReturn(0.02);
        when(riskConfig.getFixedLeverage()).thenReturn(10);
        when(riskConfig.getMaxDcaPerSymbol()).thenReturn(3);

        advisorService = new AdvisorService(
                geminiService, binanceFuturesService, tradeRecordService,
                webhookService, advisorConfig, riskConfig);
    }

    @Nested
    @DisplayName("runAdvisory 主流程")
    class RunAdvisoryTests {

        @Test
        @DisplayName("Gemini 回應成功 — 發送 Discord 通知")
        void successSendsNotification() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("AI 分析：帳戶狀態正常"));

            advisorService.runAdvisory();

            verify(webhookService).sendNotification(
                    contains("AI"),
                    eq("AI 分析：帳戶狀態正常"),
                    eq(AdvisorService.COLOR_PURPLE));
        }

        @Test
        @DisplayName("Gemini 回應為空 — 不發送通知")
        void emptyGeminiResponseSkipsNotification() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            advisorService.runAdvisory();

            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Gemini 收到正確的 context 格式")
        void geminiReceivesCorrectContext() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), contextCaptor.capture());

            String context = contextCaptor.getValue();
            assertThat(context).contains("交易帳戶狀態報告");
            assertThat(context).contains("帳戶餘額");
            assertThat(context).contains("當前持倉");
        }

        @Test
        @DisplayName("AI 回覆超長 — 截斷到 3800 字")
        void longResponseTruncated() {
            setupDefaultMocks();
            String longResponse = "A".repeat(4000);
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of(longResponse));

            advisorService.runAdvisory();

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(webhookService).sendNotification(anyString(), contentCaptor.capture(), anyInt());

            String sent = contentCaptor.getValue();
            assertThat(sent.length()).isLessThan(4000);
            assertThat(sent).contains("已截斷");
        }

        @Test
        @DisplayName("AI 回覆未超長 — 不截斷")
        void shortResponseNotTruncated() {
            setupDefaultMocks();
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("短回覆"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(webhookService).sendNotification(anyString(), contentCaptor.capture(), anyInt());
            assertThat(contentCaptor.getValue()).isEqualTo("短回覆");
        }
    }

    @Nested
    @DisplayName("context 組裝 — 各段獨立容錯")
    class ContextBuildTests {

        @Test
        @DisplayName("餘額查詢失敗 — context 仍包含其他段")
        void balanceFailureStillBuildsContext() {
            when(binanceFuturesService.getAvailableBalance()).thenThrow(new RuntimeException("timeout"));
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
            when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of("totalNetProfit", 0.0, "winRate", "0%", "profitFactor", 0.0));

            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("分析完成"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), captor.capture());
            assertThat(captor.getValue()).contains("查詢失敗");
            assertThat(captor.getValue()).contains("當前持倉");
        }

        @Test
        @DisplayName("有持倉 — context 包含持倉詳情")
        void openPositionsIncluded() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);
            Trade trade = new Trade();
            trade.setSymbol("BTCUSDT");
            trade.setSide("LONG");
            trade.setEntryPrice(50000.0);
            trade.setEntryQuantity(0.01);
            trade.setStopLoss(49000.0);
            trade.setDcaCount(1);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of(trade));
            when(binanceFuturesService.getMarkPrice("BTCUSDT")).thenReturn(51000.0);
            when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 1, "wins", 1, "losses", 0));
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(-10.0);
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of("totalNetProfit", 100.0, "winRate", "60%", "profitFactor", 1.5));

            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), captor.capture());
            String ctx = captor.getValue();
            assertThat(ctx).contains("BTCUSDT");
            assertThat(ctx).contains("LONG");
            assertThat(ctx).contains("DCA: 1");
        }

        @Test
        @DisplayName("markPrice 查詢失敗 — 顯示查詢失敗但不崩潰")
        void markPriceFailureSafe() {
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);
            Trade trade = new Trade();
            trade.setSymbol("ETHUSDT");
            trade.setSide("SHORT");
            trade.setEntryPrice(3000.0);
            trade.setEntryQuantity(0.1);
            trade.setStopLoss(null);
            when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of(trade));
            when(binanceFuturesService.getMarkPrice("ETHUSDT")).thenThrow(new RuntimeException("API error"));
            when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of());

            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("ok"));

            advisorService.runAdvisory();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateContent(anyString(), captor.capture());
            assertThat(captor.getValue()).contains("市價: 查詢失敗");
        }

        @Test
        @DisplayName("所有 service 都拋例外 — 不崩潰")
        void allServicesFail() {
            when(binanceFuturesService.getAvailableBalance()).thenThrow(new RuntimeException("fail"));
            when(tradeRecordService.findAllOpenTrades()).thenThrow(new RuntimeException("fail"));
            when(tradeRecordService.getTodayStats()).thenThrow(new RuntimeException("fail"));
            when(tradeRecordService.getTodayRealizedLoss()).thenThrow(new RuntimeException("fail"));
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenThrow(new RuntimeException("fail"));
            when(tradeRecordService.getStatsSummary()).thenThrow(new RuntimeException("fail"));

            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("分析完成"));

            assertThatCode(() -> advisorService.runAdvisory()).doesNotThrowAnyException();
            verify(webhookService).sendNotification(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("常數驗證")
    class ConstantsTests {

        @Test
        @DisplayName("COLOR_PURPLE 正確")
        void colorPurple() {
            assertThat(AdvisorService.COLOR_PURPLE).isEqualTo(0x9B59B6);
        }
    }

    // ========== helper ==========

    private void setupDefaultMocks() {
        when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);
        when(tradeRecordService.findAllOpenTrades()).thenReturn(List.of());
        when(tradeRecordService.getTodayStats()).thenReturn(Map.of("trades", 0, "wins", 0, "losses", 0));
        when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
        when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
        when(tradeRecordService.getStatsSummary()).thenReturn(
                Map.of("totalNetProfit", 0.0, "winRate", "0%", "profitFactor", 0.0));
    }
}
