package com.trader.trading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 廣播紀錄 API 回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastLogResponse {

    private List<BroadcastLogSummary> content;
    private int page;
    private int size;
    private int totalPages;
    private long totalElements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BroadcastLogSummary {
        private Long id;
        private String signalAction;
        private String symbol;
        private String side;
        private String sourceAuthor;
        private int totalUsers;
        private int successCount;
        private int failCount;
        private int skippedNoSub;
        private int skippedNoKey;
        private String status;
        private Integer aiConfidence;
        private Long durationMs;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BroadcastLogDetail {
        private Long id;
        private String signalAction;
        private String symbol;
        private String side;
        private Double entryPrice;
        private Double stopLoss;
        private Double takeProfit;
        private Double closeRatio;
        private Double newStopLoss;
        private Double newTakeProfit;
        private Boolean isDca;
        private String sourceAuthor;
        private int totalUsers;
        private int successCount;
        private int failCount;
        private int skippedNoSub;
        private int skippedNoKey;
        private String status;
        private Integer aiConfidence;
        private String aiReasoning;
        private Long durationMs;
        private LocalDateTime createdAt;
        private List<UserResult> userResults;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResult {
        private String userId;
        private String email;
        private boolean success;
        private String errorMessage;
    }
}
