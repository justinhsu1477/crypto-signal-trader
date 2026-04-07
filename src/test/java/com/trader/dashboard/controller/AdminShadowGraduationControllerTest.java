package com.trader.dashboard.controller;

import com.trader.trading.dto.signalsource.ShadowGraduationResult;
import com.trader.trading.dto.signalsource.ShadowGraduationResult.GraduationStatus;
import com.trader.trading.service.ShadowGraduationService;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminShadowGraduationController 單元測試
 *
 * 覆蓋：
 * - 有 SHADOW 來源 → 回傳評估結果列表
 * - 無 SHADOW 來源 → 回傳空列表
 */
class AdminShadowGraduationControllerTest {

    private ShadowGraduationService shadowGraduationService;
    private AdminShadowGraduationController controller;

    @BeforeEach
    void setUp() {
        shadowGraduationService = mock(ShadowGraduationService.class);
        controller = new AdminShadowGraduationController(shadowGraduationService);
    }

    private ShadowGraduationResult buildResult(Long id, String name, GraduationStatus status, int passed) {
        return ShadowGraduationResult.builder()
                .sourceId(id)
                .name(name)
                .displayName("顯示-" + name)
                .paperTradeCount(50)
                .paperWinRate(60.0)
                .paperProfitFactor(1.5)
                .paperMaxConsecutiveLosses(3)
                .paperTotalPnl(300.0)
                .tradesPass(true)
                .winRatePass(true)
                .profitFactorPass(true)
                .consecutiveLossesPass(true)
                .passedCriteria(passed)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("有 SHADOW 來源 → 200 + 評估結果列表")
    void returnsGraduationResults() {
        List<ShadowGraduationResult> results = List.of(
                buildResult(1L, "來源A", GraduationStatus.READY, 4),
                buildResult(2L, "來源B", GraduationStatus.NOT_READY, 1)
        );
        when(shadowGraduationService.evaluateAll()).thenReturn(results);

        ResponseEntity<List<ShadowGraduationResult>> response = controller.getShadowGraduation();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).getStatus()).isEqualTo(GraduationStatus.READY);
        assertThat(response.getBody().get(0).getPassedCriteria()).isEqualTo(4);
        assertThat(response.getBody().get(1).getStatus()).isEqualTo(GraduationStatus.NOT_READY);
        verify(shadowGraduationService).evaluateAll();
    }

    @Test
    @DisplayName("無 SHADOW 來源 → 200 + 空列表")
    void returnsEmptyList() {
        when(shadowGraduationService.evaluateAll()).thenReturn(List.of());

        ResponseEntity<List<ShadowGraduationResult>> response = controller.getShadowGraduation();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(shadowGraduationService).evaluateAll();
    }

    @Test
    @DisplayName("回傳結果包含完整欄位")
    void returnsCompleteFields() {
        ShadowGraduationResult result = buildResult(1L, "完整測試", GraduationStatus.APPROACHING, 3);
        when(shadowGraduationService.evaluateAll()).thenReturn(List.of(result));

        ResponseEntity<List<ShadowGraduationResult>> response = controller.getShadowGraduation();

        ShadowGraduationResult r = response.getBody().get(0);
        assertThat(r.getSourceId()).isEqualTo(1L);
        assertThat(r.getName()).isEqualTo("完整測試");
        assertThat(r.getDisplayName()).isEqualTo("顯示-完整測試");
        assertThat(r.getPaperTradeCount()).isEqualTo(50);
        assertThat(r.getPaperWinRate()).isEqualTo(60.0);
        assertThat(r.getPaperProfitFactor()).isEqualTo(1.5);
        assertThat(r.getPaperMaxConsecutiveLosses()).isEqualTo(3);
        assertThat(r.getPaperTotalPnl()).isEqualTo(300.0);
    }
}
