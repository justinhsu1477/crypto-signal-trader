package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.GeminiService;
import com.trader.notification.service.NotificationService;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.DailySignalReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailySignalReportServiceTest {

    @Mock private BroadcastLogRepository broadcastLogRepository;
    @Mock private DailySignalReportRepository dailySignalReportRepository;
    @Mock private GeminiService geminiService;
    @Mock private NotificationService notificationService;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DailySignalReportService service;

    private BroadcastLog buildLog(String symbol, String side, String action, String author, Integer confidence) {
        return BroadcastLog.builder()
                .symbol(symbol)
                .side(side)
                .signalAction(action)
                .sourceAuthor(author)
                .aiConfidence(confidence)
                .totalUsers(5)
                .successCount(3)
                .failCount(1)
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    class BuildReportDataTests {

        @Test
        void emptyLogs_returnsEmptyStats() {
            Map<String, Object> data = service.buildReportData(List.of());

            assertThat(data.get("longCount")).isEqualTo(0);
            assertThat(data.get("shortCount")).isEqualTo(0);
            assertThat(data.get("avgConfidence")).isNull();
            assertThat((List<?>) data.get("sourceStats")).isEmpty();
            assertThat((List<?>) data.get("symbolStats")).isEmpty();
        }

        @Test
        void countsLongAndShort() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "source-a", 80),
                    buildLog("ETHUSDT", "LONG", "ENTRY", "source-a", 70),
                    buildLog("BTCUSDT", "SHORT", "ENTRY", "source-b", 60)
            );

            Map<String, Object> data = service.buildReportData(logs);

            assertThat(data.get("longCount")).isEqualTo(2);
            assertThat(data.get("shortCount")).isEqualTo(1);
            assertThat((Double) data.get("avgConfidence")).isEqualTo(70.0);
        }

        @SuppressWarnings("unchecked")
        @Test
        void groupsBySource() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "alpha", 80),
                    buildLog("ETHUSDT", "SHORT", "ENTRY", "alpha", 60),
                    buildLog("BTCUSDT", "LONG", "ENTRY", "beta", 90)
            );

            Map<String, Object> data = service.buildReportData(logs);
            List<Map<String, Object>> sourceStats = (List<Map<String, Object>>) data.get("sourceStats");

            assertThat(sourceStats).hasSize(2);
            // alpha has 2, beta has 1 — sorted by count desc
            assertThat(sourceStats.get(0).get("source")).isEqualTo("alpha");
            assertThat(sourceStats.get(0).get("count")).isEqualTo(2);
            assertThat(sourceStats.get(1).get("source")).isEqualTo("beta");
            assertThat(sourceStats.get(1).get("count")).isEqualTo(1);
        }

        @SuppressWarnings("unchecked")
        @Test
        void groupsBySymbolTopTen() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "a", null),
                    buildLog("BTCUSDT", "LONG", "ENTRY", "a", null),
                    buildLog("ETHUSDT", "SHORT", "ENTRY", "a", null)
            );

            Map<String, Object> data = service.buildReportData(logs);
            List<Map<String, Object>> symbolStats = (List<Map<String, Object>>) data.get("symbolStats");

            assertThat(symbolStats).hasSize(2);
            assertThat(symbolStats.get(0).get("symbol")).isEqualTo("BTCUSDT");
            assertThat(symbolStats.get(0).get("count")).isEqualTo(2L);
        }

        @Test
        void nullSourceAuthor_groupedAsUnknown() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", null, null)
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sourceStats =
                    (List<Map<String, Object>>) service.buildReportData(logs).get("sourceStats");

            assertThat(sourceStats).hasSize(1);
            assertThat(sourceStats.get(0).get("source")).isEqualTo("unknown");
        }
    }

    @Nested
    class GenerateReportTests {

        @BeforeEach
        void setup() {
            when(dailySignalReportRepository.findByReportDate(any())).thenReturn(Optional.empty());
            when(dailySignalReportRepository.save(any())).thenAnswer(inv -> {
                DailySignalReport r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });
        }

        @Test
        void noLogs_savesEmptyReport_skipsAi() {
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());

            DailySignalReport result = service.generateReportForDate(LocalDate.of(2026, 3, 13));

            assertThat(result.getTotalSignals()).isZero();
            assertThat(result.getAiAnalysis()).isNull();
            verify(geminiService, never()).generateContent(anyString(), anyString());
            verify(notificationService).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        void fewLogs_belowThreshold_skipsAi() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "a", 80),
                    buildLog("ETHUSDT", "SHORT", "ENTRY", "a", 70)
            );
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(logs);

            service.generateReportForDate(LocalDate.of(2026, 3, 13));

            verify(geminiService, never()).generateContent(anyString(), anyString());
        }

        @Test
        void enoughLogs_callsGemini() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "a", 80),
                    buildLog("ETHUSDT", "SHORT", "ENTRY", "b", 70),
                    buildLog("SOLUSDT", "LONG", "CLOSE", "a", 60)
            );
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(logs);
            when(geminiService.generateContent(anyString(), anyString()))
                    .thenReturn(Optional.of("AI analysis result"));

            DailySignalReport result = service.generateReportForDate(LocalDate.of(2026, 3, 13));

            verify(geminiService).generateContent(anyString(), anyString());
            assertThat(result.getAiAnalysis()).isEqualTo("AI analysis result");
            assertThat(result.getTotalSignals()).isEqualTo(3);
        }

        @Test
        void existingReport_deletedAndRecreated() {
            DailySignalReport existing = DailySignalReport.builder().id(99L).build();
            when(dailySignalReportRepository.findByReportDate(any())).thenReturn(Optional.of(existing));
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());

            service.generateReportForDate(LocalDate.of(2026, 3, 13));

            verify(dailySignalReportRepository).delete(existing);
            verify(dailySignalReportRepository).save(any());
        }

        @Test
        void savedReport_hasCorrectStats() {
            List<BroadcastLog> logs = List.of(
                    buildLog("BTCUSDT", "LONG", "ENTRY", "alpha", 80),
                    buildLog("ETHUSDT", "SHORT", "ENTRY", "beta", 60)
            );
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(logs);

            service.generateReportForDate(LocalDate.of(2026, 3, 13));

            ArgumentCaptor<DailySignalReport> captor = ArgumentCaptor.forClass(DailySignalReport.class);
            verify(dailySignalReportRepository).save(captor.capture());

            DailySignalReport saved = captor.getValue();
            assertThat(saved.getReportDate()).isEqualTo(LocalDate.of(2026, 3, 13));
            assertThat(saved.getTotalSignals()).isEqualTo(2);
            assertThat(saved.getTotalSources()).isEqualTo(2);
            assertThat(saved.getLongCount()).isEqualTo(1);
            assertThat(saved.getShortCount()).isEqualTo(1);
            assertThat(saved.getAvgConfidence()).isEqualTo(70.0);
        }

        @Test
        void adminNotification_sentAfterSave() {
            when(broadcastLogRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());

            service.generateReportForDate(LocalDate.of(2026, 3, 13));

            verify(notificationService).sendNotificationToAdmins(
                    contains("2026-03-13"), anyString(), anyInt());
        }
    }

    @Nested
    class QueryTests {

        @Test
        void getReportById_delegatesToRepository() {
            DailySignalReport report = DailySignalReport.builder().id(1L).build();
            when(dailySignalReportRepository.findById(1L)).thenReturn(Optional.of(report));

            Optional<DailySignalReport> result = service.getReportById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
        }
    }
}
