package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.advisor.dto.RiskLevel;
import com.trader.advisor.dto.SignalScore;
import com.trader.shared.model.TradeRequest;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SignalScoringService 單元測試
 *
 * 測試重點：
 * - 專用 scoringExecutor 隔離
 * - bounded queue 滿時降級（回傳 null + 記錄 discarded）
 * - ScoringMetrics 正確更新
 * - 功能關閉/非 ENTRY 時跳過（零開銷）
 */
class SignalScoringServiceTest {

    private GeminiService mockGemini;
    private AdvisorConfig mockConfig;
    private ThreadPoolExecutor scoringExecutor;
    private ScoringMetrics scoringMetrics;
    private SignalScoringService service;

    @BeforeEach
    void setUp() {
        mockGemini = mock(GeminiService.class);
        mockConfig = mock(AdvisorConfig.class);

        // 測試用：core=1, max=1, queue=1 → 非常小，方便測「滿了降級」
        scoringExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );

        scoringMetrics = new ScoringMetrics(scoringExecutor);
        service = new SignalScoringService(mockGemini, mockConfig, scoringExecutor, scoringMetrics);
    }

    @AfterEach
    void tearDown() {
        scoringExecutor.shutdownNow();
    }

    private TradeRequest createEntryRequest() {
        TradeRequest request = new TradeRequest();
        request.setAction("ENTRY");
        request.setSymbol("BTCUSDT");
        request.setSide("LONG");
        request.setEntryPrice(95000.0);
        request.setStopLoss(93000.0);
        request.setTakeProfit(99000.0);
        return request;
    }

    // ==================== 基本功能 ====================

    @Test
    @DisplayName("評分開啟 + ENTRY → 呼叫 Gemini 並回傳分數")
    void scoringEnabled_entrySignal_returnsScore() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);
        when(mockGemini.generateContent(anyString(), anyString()))
                .thenReturn(Optional.of("{\"confidence\":78,\"riskLevel\":\"MEDIUM\",\"reasoning\":\"測試理由\"}"));

        CompletableFuture<SignalScore> future = service.scoreAsync(createEntryRequest());
        SignalScore score = future.get(5, TimeUnit.SECONDS);

        assertThat(score).isNotNull();
        assertThat(score.getConfidence()).isEqualTo(78);
        assertThat(score.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(score.getReasoning()).isEqualTo("測試理由");
        assertThat(scoringMetrics.getScoredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("評分關閉 → 立即回傳 null + 記錄 skipped")
    void scoringDisabled_returnsNull() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(false);

        CompletableFuture<SignalScore> future = service.scoreAsync(createEntryRequest());
        SignalScore score = future.get(1, TimeUnit.SECONDS);

        assertThat(score).isNull();
        assertThat(scoringMetrics.getSkippedCount()).isEqualTo(1);
        verify(mockGemini, never()).generateContent(anyString(), anyString());
    }

    @Test
    @DisplayName("非 ENTRY 信號 → 立即回傳 null + 記錄 skipped")
    void nonEntrySignal_returnsNull() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);

        TradeRequest closeRequest = new TradeRequest();
        closeRequest.setAction("CLOSE");
        closeRequest.setSymbol("BTCUSDT");

        CompletableFuture<SignalScore> future = service.scoreAsync(closeRequest);
        SignalScore score = future.get(1, TimeUnit.SECONDS);

        assertThat(score).isNull();
        assertThat(scoringMetrics.getSkippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gemini 回傳空 → 回傳 null + 記錄 failed")
    void geminiNoResponse_returnsNull() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);
        when(mockGemini.generateContent(anyString(), anyString()))
                .thenReturn(Optional.empty());

        CompletableFuture<SignalScore> future = service.scoreAsync(createEntryRequest());
        SignalScore score = future.get(5, TimeUnit.SECONDS);

        assertThat(score).isNull();
        assertThat(scoringMetrics.getFailedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gemini 回傳無效 JSON → 回傳 null + 記錄 failed")
    void geminiInvalidJson_returnsNull() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);
        when(mockGemini.generateContent(anyString(), anyString()))
                .thenReturn(Optional.of("not a json"));

        CompletableFuture<SignalScore> future = service.scoreAsync(createEntryRequest());
        SignalScore score = future.get(5, TimeUnit.SECONDS);

        assertThat(score).isNull();
        assertThat(scoringMetrics.getFailedCount()).isEqualTo(1);
    }

    // ==================== 線程池降級 ====================

    @Test
    @DisplayName("線程池滿 → 降級回傳 null + 記錄 discarded")
    void executorFull_gracefulDegradation() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);

        // 讓 Gemini 慢慢回（5 秒），佔住線程池
        CountDownLatch blockLatch = new CountDownLatch(1);
        when(mockGemini.generateContent(anyString(), anyString()))
                .thenAnswer(inv -> {
                    blockLatch.await(10, TimeUnit.SECONDS);
                    return Optional.of("{\"confidence\":50,\"riskLevel\":\"MEDIUM\",\"reasoning\":\"slow\"}");
                });

        // 第 1 個：佔住唯一的 core 線程
        service.scoreAsync(createEntryRequest());
        Thread.sleep(50);  // 等線程啟動

        // 第 2 個：進入 queue（容量 1）
        service.scoreAsync(createEntryRequest());

        // 第 3 個：應該被拒絕 → 降級回傳 null
        CompletableFuture<SignalScore> rejected = service.scoreAsync(createEntryRequest());
        SignalScore result = rejected.get(1, TimeUnit.SECONDS);

        assertThat(result).isNull();
        assertThat(scoringMetrics.getDiscardedCount()).isGreaterThanOrEqualTo(1);

        // 釋放阻塞
        blockLatch.countDown();
    }

    // ==================== 指標觀測 ====================

    @Test
    @DisplayName("ScoringMetrics 正確追蹤延遲")
    void metricsTrackLatency() throws Exception {
        when(mockConfig.isScoringEnabled()).thenReturn(true);
        when(mockGemini.generateContent(anyString(), anyString()))
                .thenReturn(Optional.of("{\"confidence\":85,\"riskLevel\":\"LOW\",\"reasoning\":\"好信號\"}"));

        service.scoreAsync(createEntryRequest()).get(5, TimeUnit.SECONDS);

        assertThat(scoringMetrics.getScoredCount()).isEqualTo(1);
        assertThat(scoringMetrics.getAvgLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(scoringMetrics.getMaxLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getSummary 回傳完整結構")
    void getSummary_returnsAllFields() {
        var summary = scoringMetrics.getSummary();
        assertThat(summary).containsKeys(
                "scored", "discarded", "failed", "skipped",
                "avgLatencyMs", "maxLatencyMs", "queueSize", "activeThreads");
    }
}
