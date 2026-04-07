package com.trader.trading.service;

import com.trader.trading.config.ShadowGraduationConfig;
import com.trader.trading.dto.signalsource.ShadowGraduationResult;
import com.trader.trading.dto.signalsource.ShadowGraduationResult.GraduationStatus;
import com.trader.trading.dto.signalsource.SignalSourcePerformanceDto;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ShadowGraduationService 單元測試
 *
 * 覆蓋：
 * - 四項指標全通過 → READY
 * - 三項通過 → APPROACHING
 * - 兩項以下通過 → NOT_READY
 * - 無 SHADOW 來源 → 空列表
 * - 只篩選 SHADOW + paperTradingEnabled 來源
 * - 績效查詢失敗 → NOT_READY（graceful degradation）
 * - 邊界值測試（等於門檻值）
 */
class ShadowGraduationServiceTest {

    private SignalSourceConfigRepository sourceRepository;
    private SignalSourceService signalSourceService;
    private ShadowGraduationConfig config;
    private ShadowGraduationService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SignalSourceConfigRepository.class);
        signalSourceService = mock(SignalSourceService.class);
        config = new ShadowGraduationConfig();
        // 使用預設門檻：minTrades=30, minWinRate=55.0, minProfitFactor=1.3, maxConsecutiveLosses=5
        service = new ShadowGraduationService(sourceRepository, signalSourceService, config);
    }

    // ==================== Helper ====================

    private SignalSourceConfig buildShadowSource(Long id, String name) {
        return SignalSourceConfig.builder()
                .id(id)
                .name(name)
                .displayName("顯示-" + name)
                .channelId("ch-" + id)
                .guildId("g-" + id)
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .paperTradingEnabled(true)
                .enabled(true)
                .build();
    }

    private SignalSourceConfig buildAutoSource(Long id, String name) {
        return SignalSourceConfig.builder()
                .id(id)
                .name(name)
                .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                .paperTradingEnabled(false)
                .enabled(true)
                .build();
    }

    private SignalSourcePerformanceDto buildPerf(long paperCount, double paperWinRate,
                                                  double paperPF, int paperMaxLosses, double paperPnl) {
        return SignalSourcePerformanceDto.builder()
                .paperTradeCount(paperCount)
                .paperWinRate(paperWinRate)
                .paperProfitFactor(paperPF)
                .paperMaxConsecutiveLosses(paperMaxLosses)
                .paperTotalPnl(paperPnl)
                .build();
    }

    // ==================== 畢業狀態判定 ====================

    @Nested
    @DisplayName("evaluateAll - 畢業狀態判定")
    class EvaluateAll {

        @Test
        @DisplayName("4/4 通過 → READY")
        void allCriteriaPass_ready() {
            SignalSourceConfig source = buildShadowSource(1L, "軍長策略");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenReturn(buildPerf(50, 61.0, 1.8, 3, 500.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).hasSize(1);
            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.READY);
            assertThat(r.getPassedCriteria()).isEqualTo(4);
            assertThat(r.isTradesPass()).isTrue();
            assertThat(r.isWinRatePass()).isTrue();
            assertThat(r.isProfitFactorPass()).isTrue();
            assertThat(r.isConsecutiveLossesPass()).isTrue();
            assertThat(r.getSourceId()).isEqualTo(1L);
            assertThat(r.getName()).isEqualTo("軍長策略");
            assertThat(r.getDisplayName()).isEqualTo("顯示-軍長策略");
        }

        @Test
        @DisplayName("3/4 通過 → APPROACHING")
        void threeCriteriaPass_approaching() {
            SignalSourceConfig source = buildShadowSource(2L, "大鏢客");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            // 筆數不足（28 < 30），其他三項通過
            when(signalSourceService.getSourcePerformance(2L, "all"))
                    .thenReturn(buildPerf(28, 58.0, 1.5, 4, 200.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.APPROACHING);
            assertThat(r.getPassedCriteria()).isEqualTo(3);
            assertThat(r.isTradesPass()).isFalse();
            assertThat(r.isWinRatePass()).isTrue();
            assertThat(r.isProfitFactorPass()).isTrue();
            assertThat(r.isConsecutiveLossesPass()).isTrue();
        }

        @Test
        @DisplayName("2/4 通過 → NOT_READY")
        void twoCriteriaPass_notReady() {
            SignalSourceConfig source = buildShadowSource(3L, "比特幣飛揚");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            // 勝率低 + PF 低
            when(signalSourceService.getSourcePerformance(3L, "all"))
                    .thenReturn(buildPerf(40, 40.0, 0.7, 3, -100.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.NOT_READY);
            assertThat(r.getPassedCriteria()).isEqualTo(2);
            assertThat(r.isTradesPass()).isTrue();
            assertThat(r.isWinRatePass()).isFalse();
            assertThat(r.isProfitFactorPass()).isFalse();
            assertThat(r.isConsecutiveLossesPass()).isTrue();
        }

        @Test
        @DisplayName("0/4 通過 → NOT_READY")
        void noCriteriaPass_notReady() {
            SignalSourceConfig source = buildShadowSource(4L, "新來源");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            when(signalSourceService.getSourcePerformance(4L, "all"))
                    .thenReturn(buildPerf(5, 30.0, 0.5, 8, -200.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.NOT_READY);
            assertThat(r.getPassedCriteria()).isEqualTo(0);
        }
    }

    // ==================== 篩選邏輯 ====================

    @Nested
    @DisplayName("evaluateAll - 來源篩選")
    class Filtering {

        @Test
        @DisplayName("無 SHADOW 來源 → 空列表")
        void noShadowSources_emptyList() {
            SignalSourceConfig autoSource = buildAutoSource(1L, "正式來源");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(autoSource));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).isEmpty();
            verify(signalSourceService, never()).getSourcePerformance(anyLong(), anyString());
        }

        @Test
        @DisplayName("SHADOW 但 paperTradingEnabled=false → 不評估")
        void shadowWithoutPaperTrading_excluded() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).name("只記錄不模擬")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                    .paperTradingEnabled(false)
                    .enabled(true)
                    .build();
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("混合來源 → 只評估 SHADOW + paperTradingEnabled")
        void mixedSources_onlyShadowEvaluated() {
            SignalSourceConfig shadow = buildShadowSource(1L, "SHADOW來源");
            SignalSourceConfig auto = buildAutoSource(2L, "AUTO來源");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(shadow, auto));
            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenReturn(buildPerf(50, 60.0, 1.5, 3, 300.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getSourceId()).isEqualTo(1L);
            verify(signalSourceService, never()).getSourcePerformance(eq(2L), anyString());
        }
    }

    // ==================== 異常處理 ====================

    @Nested
    @DisplayName("evaluateAll - 異常處理")
    class ErrorHandling {

        @Test
        @DisplayName("績效查詢失敗 → NOT_READY（不影響其他來源）")
        void performanceQueryFails_gracefulDegradation() {
            SignalSourceConfig source1 = buildShadowSource(1L, "來源A");
            SignalSourceConfig source2 = buildShadowSource(2L, "來源B");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source1, source2));

            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenThrow(new RuntimeException("DB error"));
            when(signalSourceService.getSourcePerformance(2L, "all"))
                    .thenReturn(buildPerf(50, 60.0, 1.5, 3, 300.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).hasSize(2);
            // source1 失敗 → NOT_READY
            assertThat(results.get(0).getStatus()).isEqualTo(GraduationStatus.NOT_READY);
            assertThat(results.get(0).getPassedCriteria()).isEqualTo(0);
            // source2 正常評估
            assertThat(results.get(1).getStatus()).isEqualTo(GraduationStatus.READY);
        }
    }

    // ==================== 邊界值 ====================

    @Nested
    @DisplayName("evaluateAll - 邊界值測試")
    class EdgeCases {

        @Test
        @DisplayName("剛好等於門檻值 → 全部通過（≥ 而非 >）")
        void exactlyAtThreshold_passes() {
            SignalSourceConfig source = buildShadowSource(1L, "邊界來源");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            // 每項指標剛好等於門檻
            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenReturn(buildPerf(30, 55.0, 1.3, 5, 0.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.READY);
            assertThat(r.getPassedCriteria()).isEqualTo(4);
        }

        @Test
        @DisplayName("paperTradeCount = 0 → NOT_READY")
        void zeroTrades_notReady() {
            SignalSourceConfig source = buildShadowSource(1L, "空來源");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));
            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenReturn(buildPerf(0, 0.0, 0.0, 0, 0.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            ShadowGraduationResult r = results.get(0);
            assertThat(r.getStatus()).isEqualTo(GraduationStatus.NOT_READY);
            // 0 trades → tradesPass false; 0% winRate → false; 0 PF → false; 0 losses → passes (0 ≤ 5)
            assertThat(r.getPassedCriteria()).isEqualTo(1);
            assertThat(r.isConsecutiveLossesPass()).isTrue();
        }

        @Test
        @DisplayName("多個來源 — 各自獨立評估")
        void multipleSources_evaluatedIndependently() {
            SignalSourceConfig ready = buildShadowSource(1L, "優秀");
            SignalSourceConfig notReady = buildShadowSource(2L, "普通");
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(ready, notReady));
            when(signalSourceService.getSourcePerformance(1L, "all"))
                    .thenReturn(buildPerf(50, 65.0, 2.0, 2, 800.0));
            when(signalSourceService.getSourcePerformance(2L, "all"))
                    .thenReturn(buildPerf(10, 40.0, 0.8, 7, -50.0));

            List<ShadowGraduationResult> results = service.evaluateAll();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getStatus()).isEqualTo(GraduationStatus.READY);
            assertThat(results.get(1).getStatus()).isEqualTo(GraduationStatus.NOT_READY);
        }
    }
}
