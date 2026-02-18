package com.trader.shared.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderResult {

    private boolean success;
    private String orderId;
    private String symbol;
    private String side;
    private String type;
    private double price;
    private double quantity;
    private String errorMessage;
    private String rawResponse;
    private String riskSummary;  // 風控摘要（入場時填入：餘額、1R、保證金等）

    public static OrderResult fail(String errorMessage) {
        return OrderResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
