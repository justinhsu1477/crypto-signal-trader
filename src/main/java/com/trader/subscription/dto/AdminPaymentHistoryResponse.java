package com.trader.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理員查看用戶付款歷史
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaymentHistoryResponse {

    // ── 用戶資訊 ──
    private String userId;
    private String email;
    private String name;

    // ── 付款紀錄 ──
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
        private String walletAddress;
        private BigDecimal amount;
        private String currency;
        private String status;           // succeeded / failed / refunded
        private LocalDateTime paidAt;
        private LocalDateTime createdAt;

        // ── 關聯的訂閱 ──
        private Long subscriptionId;
        private String planId;
    }
}
