package com.trader.papertrade.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.papertrade.dto.PromotionRecommendation;
import com.trader.papertrade.dto.SourcePerformanceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test PaperPromotionEvaluator 決策邏輯。每筆 metrics 應被正確分類 PROMOTE / MONITOR / REJECT。
 */
class PaperPromotionEvaluatorTest {

    private PaperPerformanceService performanceService;
    private DiscordWebhookService discordWebhookService;
    private PaperPromotionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        performanceService = mock(PaperPerformanceService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        evaluator = new PaperPromotionEvaluator(performanceService, discordWebhookService);
        // 預設門檻 (對齊 @Value default)
        ReflectionTestUtils.setField(evaluator, "minTrades", 30);
        ReflectionTestUtils.setField(evaluator, "minWinRate", 0.55);
        ReflectionTestUtils.setField(evaluator, "minProfitFactor", 1.5);
        ReflectionTestUtils.setField(evaluator, "maxDrawdownPct", 0.20);
        ReflectionTestUtils.setField(evaluator, "minPeriodDays", 30L);
        ReflectionTestUtils.setField(evaluator, "enabled", true);
    }

    @Nested
    @DisplayName("evaluateOne — 個別決策")
    class IndividualDecision {

        @Test
        @DisplayName("全達標 → PROMOTE")
        void allPass_promote() {
            SourcePerformanceMetrics m = goodMetrics();
            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.PROMOTE);
            assertThat(r.getReasons()).allMatch(s -> s.startsWith("✅"));
        }

        @Test
        @DisplayName("trades 不足 → MONITOR (非 hard reject)")
        void tooFewTrades_monitor() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setClosedTrades(10);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.MONITOR);
            assertThat(r.getReasons()).anyMatch(s -> s.contains("trades 10 < 30"));
        }

        @Test
        @DisplayName("觀察期不足 → MONITOR")
        void tooShortPeriod_monitor() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setPeriodDays(10);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.MONITOR);
        }

        @Test
        @DisplayName("負 PnL → REJECT (hard)")
        void negativePnl_reject() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setTotalPnl(-500);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.REJECT);
            assertThat(r.getReasons()).anyMatch(s -> s.contains("totalPnl") && s.contains("< 0"));
        }

        @Test
        @DisplayName("win rate < 35% → REJECT (very poor)")
        void veryLowWinRate_reject() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setWinRate(0.30);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.REJECT);
        }

        @Test
        @DisplayName("win rate 50% (低於 55% 但 > 35%) → MONITOR")
        void mediumWinRate_monitor() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setWinRate(0.50);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.MONITOR);
        }

        @Test
        @DisplayName("DD 超標 → MONITOR")
        void highDrawdown_monitor() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setMaxDrawdownPct(0.35);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.MONITOR);
        }

        @Test
        @DisplayName("Profit factor 為 Infinity (沒 losses) → 視為 pass")
        void infinityProfitFactor_pass() {
            SourcePerformanceMetrics m = goodMetrics();
            m.setProfitFactor(Double.POSITIVE_INFINITY);

            PromotionRecommendation r = evaluator.evaluateOne(m);

            assertThat(r.getDecision()).isEqualTo(PromotionRecommendation.Decision.PROMOTE);
        }
    }

    @Nested
    @DisplayName("evaluateAndNotify — 整合 + 通知")
    class IntegrationFlow {

        @Test
        @DisplayName("有 PROMOTE candidate → 發 Discord")
        void hasPromoteCandidates_sendsDiscord() {
            when(performanceService.getAllSourceMetrics()).thenReturn(List.of(goodMetrics()));

            evaluator.evaluateAndNotify();

            verify(discordWebhookService, times(1)).sendNotification(
                    contains("Promotion Candidates"), anyString(), anyInt());
        }

        @Test
        @DisplayName("無 PROMOTE candidate → 不發 Discord")
        void noPromoteCandidates_noDiscord() {
            SourcePerformanceMetrics monitor = goodMetrics();
            monitor.setClosedTrades(10);  // 太少 → MONITOR
            when(performanceService.getAllSourceMetrics()).thenReturn(List.of(monitor));

            evaluator.evaluateAndNotify();

            verify(discordWebhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("空 metrics → 不發 Discord，不報錯")
        void emptyMetrics_silentSkip() {
            when(performanceService.getAllSourceMetrics()).thenReturn(List.of());

            evaluator.evaluateAndNotify();

            verify(discordWebhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Discord 通知失敗 → 不傳染 exception")
        void discordFailureSwallowed() {
            doThrow(new RuntimeException("Discord down"))
                    .when(discordWebhookService).sendNotification(anyString(), anyString(), anyInt());
            when(performanceService.getAllSourceMetrics()).thenReturn(List.of(goodMetrics()));

            // 不該 throw
            evaluator.evaluateAndNotify();
        }
    }

    @Nested
    @DisplayName("runDailyEvaluation kill switch")
    class KillSwitch {

        @Test
        @DisplayName("enabled=false → skip 完整 flow")
        void disabled_skipsEverything() {
            ReflectionTestUtils.setField(evaluator, "enabled", false);

            evaluator.runDailyEvaluation();

            verifyNoInteractions(performanceService);
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("evaluateAndNotify 內部錯誤被 swallow")
        void internalErrorSwallowed() {
            ReflectionTestUtils.setField(evaluator, "enabled", true);
            when(performanceService.getAllSourceMetrics())
                    .thenThrow(new RuntimeException("DB down"));

            evaluator.runDailyEvaluation();  // 不 throw 才對
        }
    }

    // ==================== helpers ====================

    /** 完全達標的 metric — 各 test override 一個欄位驗證該欄位的影響。 */
    private static SourcePerformanceMetrics goodMetrics() {
        return SourcePerformanceMetrics.builder()
                .sourceId(1L)
                .displayName("良好源")
                .channelId("ch1")
                .closedTrades(50)
                .wins(35)
                .losses(15)
                .winRate(0.70)
                .totalPnl(3500.0)
                .avgPnl(70.0)
                .profitFactor(2.5)
                .maxDrawdownPct(0.10)
                .sharpeRatio(1.5)
                .expectancy(70.0)
                .periodDays(60)
                .build();
    }
}
