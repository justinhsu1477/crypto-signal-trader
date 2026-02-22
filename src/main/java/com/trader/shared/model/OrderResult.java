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
    private double commission;   // Binance 回傳的實際手續費（USDT），0 表示尚未取得
    private String errorMessage;
    private String rawResponse;
    private String riskSummary;  // 風控摘要（入場時填入：餘額、1R、保證金等）
    private Double netProfit;       // 平倉淨利（手動 CLOSE 時由 recordClose 回填）
    private Double totalCommission; // 平倉總手續費（入場 + 出場）

    public static OrderResult fail(String errorMessage) {
        return OrderResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
