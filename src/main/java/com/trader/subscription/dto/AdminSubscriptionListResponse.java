package com.trader.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理員訂閱總覽 — 所有用戶的訂閱狀態 + 付款摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSubscriptionListResponse {

    private List<UserSubscriptionSummary> subscriptions;
    private long totalUsers;
    private long activeSubscriptions;
    private long trialingSubscriptions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSubscriptionSummary {

        // ── 用戶資訊 ──
        private String userId;
        private String email;
        private String name;
        private boolean enabled;

        // ── 訂閱資訊 ──
        private String planId;
        private String planName;
        private String status;           // ACTIVE / TRIALING / CANCELLED / PAST_DUE / NONE
        private LocalDateTime currentPeriodStart;
        private LocalDateTime currentPeriodEnd;
        private LocalDateTime subscriptionCreatedAt;

        // ── 付款摘要 ──
        private int totalPayments;
        private BigDecimal totalAmountPaid;
    }
}
