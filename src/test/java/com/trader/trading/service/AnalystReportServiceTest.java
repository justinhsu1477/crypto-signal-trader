package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.GeminiService;
import com.trader.notification.service.NotificationService;
import com.trader.trading.entity.AnalystDailyMessage;
import com.trader.trading.entity.AnalystReport;
import com.trader.trading.repository.AnalystReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalystReportServiceTest {

    @Mock private AnalystMessageService analystMessageService;
    @Mock private AnalystReportRepository reportRepository;
    @Mock private GeminiService geminiService;
    @Mock private NotificationService notificationService;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AnalystReportService service;

    @Test
    void generateReport_throwsWhenNoMessages() {
        LocalDate date = LocalDate.of(2026, 3, 30);
        when(reportRepository.findByReportDate(date)).thenReturn(Optional.empty());
        when(analystMessageService.getMessagesByDate(date)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateReport(date))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("無分析師訊息");
    }

    @Test
    void generateReport_success() {
        LocalDate date = LocalDate.of(2026, 3, 30);
        when(reportRepository.findByReportDate(date)).thenReturn(Optional.empty());

        List<AnalystDailyMessage> messages = List.of(
                AnalystDailyMessage.builder()
                        .analystName("Analyst_A")
                        .channelId("ch1")
                        .messageDate(date)
                        .content("BTC will pump to 80K")
                        .messageCount(3)
                        .build(),
                AnalystDailyMessage.builder()
                        .analystName("Analyst_B")
                        .channelId("ch2")
                        .messageDate(date)
                        .content("ETH bearish, short below 3400")
                        .messageCount(2)
                        .build()
        );
        when(analystMessageService.getMessagesByDate(date)).thenReturn(messages);
        when(geminiService.generateContent(anyString(), anyString()))
                .thenReturn(Optional.of("AI generated report content"));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnalystReport report = service.generateReport(date);

        assertThat(report.getReportDate()).isEqualTo(date);
        assertThat(report.getAnalystCount()).isEqualTo(2);
        assertThat(report.getReportContent()).isEqualTo("AI generated report content");

        ArgumentCaptor<AnalystReport> captor = ArgumentCaptor.forClass(AnalystReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getReportData()).contains("Analyst_A");
    }

    @Test
    void generateReport_overwritesExisting() {
        LocalDate date = LocalDate.of(2026, 3, 30);
        AnalystReport existing = AnalystReport.builder().id(1L).reportDate(date).build();
        when(reportRepository.findByReportDate(date)).thenReturn(Optional.of(existing));

        List<AnalystDailyMessage> messages = List.of(
                AnalystDailyMessage.builder()
                        .analystName("Analyst_A")
                        .channelId("ch1")
                        .content("content")
                        .messageCount(1)
                        .build()
        );
        when(analystMessageService.getMessagesByDate(date)).thenReturn(messages);
        when(geminiService.generateContent(anyString(), anyString()))
                .thenReturn(Optional.of("new report"));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateReport(date);

        verify(reportRepository).delete(existing);
        verify(reportRepository).save(any(AnalystReport.class));
    }

    @Test
    void generateReport_handlesAiFailure() {
        LocalDate date = LocalDate.of(2026, 3, 30);
        when(reportRepository.findByReportDate(date)).thenReturn(Optional.empty());

        List<AnalystDailyMessage> messages = List.of(
                AnalystDailyMessage.builder()
                        .analystName("Analyst_A")
                        .channelId("ch1")
                        .content("some content")
                        .messageCount(1)
                        .build()
        );
        when(analystMessageService.getMessagesByDate(date)).thenReturn(messages);
        when(geminiService.generateContent(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnalystReport report = service.generateReport(date);

        assertThat(report.getReportContent()).isNull();
        assertThat(report.getAnalystCount()).isEqualTo(1);
    }
}
