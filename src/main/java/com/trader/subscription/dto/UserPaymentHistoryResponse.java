package com.trader.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用戶查看自己的付款歷史
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPaymentHistoryResponse {

    private List<PaymentRecord> payments;
    private int totalPayments;
    private BigDecimal totalAmountPaid;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRecord {
        private Long id;
        private String txHash;
        private String network;
        private BigDecimal amount;
        private String currency;
        private String status;           // succeeded / failed / refunded
        private String planId;
        private LocalDateTime paidAt;
        private LocalDateTime createdAt;
    }
}
