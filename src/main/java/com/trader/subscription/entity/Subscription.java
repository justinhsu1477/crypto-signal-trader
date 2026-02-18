package com.trader.subscription.entity;

import jakarta.persistence.*;
import lombok.*;

import com.trader.shared.config.AppConstants;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "subscriptions", indexes = {
        @Index(name = "idx_sub_user_id", columnList = "userId"),
        @Index(name = "idx_sub_stripe_id", columnList = "stripeSubscriptionId")
})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** Stripe Customer ID (cus_xxx) */
    private String stripeCustomerId;

    /** Stripe Subscription ID (sub_xxx) */
    private String stripeSubscriptionId;

    /** 方案 ID（對應 Stripe Price ID，例如 "basic", "pro"） */
    private String planId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.TRIALING;

    /** 當期開始時間 */
    private LocalDateTime currentPeriodStart;

    /** 當期結束時間（過了這個時間 Stripe 會自動扣款或取消） */
    private LocalDateTime currentPeriodEnd;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        TRIALING,    // 免費試用中
        ACTIVE,      // 付費生效中
        CANCELLED,   // 已取消（期滿後停止）
        PAST_DUE     // 扣款失敗，寬限期中
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
