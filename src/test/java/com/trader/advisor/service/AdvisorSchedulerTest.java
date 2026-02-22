package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdvisorScheduler 單元測試
 *
 * 覆蓋：排程啟停、手動觸發、例外處理
 */
class AdvisorSchedulerTest {

    private AdvisorService advisorService;
    private AdvisorConfig advisorConfig;
    private AdvisorScheduler scheduler;

    @BeforeEach
    void setUp() {
        advisorService = mock(AdvisorService.class);
        advisorConfig = mock(AdvisorConfig.class);
        scheduler = new AdvisorScheduler(advisorService, advisorConfig);
    }

    @Nested
    @DisplayName("scheduledAdvisory — 排程觸發")
    class ScheduledTests {

        @Test
        @DisplayName("enabled — 執行 runAdvisory")
        void enabledRunsAdvisory() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            scheduler.scheduledAdvisory();

            verify(advisorService).runAdvisory();
        }

        @Test
        @DisplayName("disabled — 跳過不執行")
        void disabledSkips() {
            when(advisorConfig.isEnabled()).thenReturn(false);

            scheduler.scheduledAdvisory();

            verify(advisorService, never()).runAdvisory();
        }

        @Test
        @DisplayName("runAdvisory 拋例外 — 不向外拋出")
        void exceptionCaught() {
            when(advisorConfig.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("Gemini API error")).when(advisorService).runAdvisory();

            assertThatCode(() -> scheduler.scheduledAdvisory()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("triggerManually — 手動觸發 API")
    class ManualTriggerTests {

        @Test
        @DisplayName("enabled + 成功 — 回傳 success")
        void enabledSuccess() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "success");
            verify(advisorService).runAdvisory();
        }

        @Test
        @DisplayName("disabled — 回傳 disabled 狀態")
        void disabledReturnsDisabled() {
            when(advisorConfig.isEnabled()).thenReturn(false);

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "disabled");
            verify(advisorService, never()).runAdvisory();
        }

        @Test
        @DisplayName("runAdvisory 拋例外 — 回傳 500 error")
        void exceptionReturnsError() {
            when(advisorConfig.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("DB down")).when(advisorService).runAdvisory();

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).containsEntry("status", "error");
            assertThat(response.getBody().get("message")).contains("DB down");
        }
    }
}
