package com.trader.trading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每日訊號日報 API 回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailySignalReportResponse {

    private List<ReportSummary> content;
    private int page;
    private int size;
    private int totalPages;
    private long totalElements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportSummary {
        private Long id;
        private LocalDate reportDate;
        private int totalSignals;
        private int totalSources;
        private int longCount;
        private int shortCount;
        private Double avgConfidence;
        private boolean hasAiAnalysis;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportDetail {
        private Long id;
        private LocalDate reportDate;
        private int totalSignals;
        private int totalSources;
        private int longCount;
        private int shortCount;
        private Double avgConfidence;
        private String reportData;    // JSON string
        private String aiAnalysis;
        private Integer aiTokensUsed;
        private LocalDateTime createdAt;
    }
}
