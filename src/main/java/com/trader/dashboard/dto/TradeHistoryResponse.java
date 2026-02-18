package com.trader.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 交易歷史 DTO（含分頁）
 */
@Data
@Builder
public class TradeHistoryResponse {

    private List<TradeRecord> trades;
    private Pagination pagination;

    @Data
    @Builder
    public static class TradeRecord {
        private String tradeId;
        private String symbol;
        private String side;
        private Double entryPrice;
        private Double exitPrice;
        private Double entryQuantity;
        private Double netProfit;
        private String exitReason;
        private String signalSource;
        private Integer dcaCount;
        private String entryTime;
        private String exitTime;
        private String status;
    }

    @Data
    @Builder
    public static class Pagination {
        private int page;
        private int size;
        private int totalPages;
        private long totalElements;
    }
}
