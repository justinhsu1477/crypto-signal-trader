package com.trader.subscription.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_history", indexes = {
        @Index(name = "idx_ph_user_id", columnList = "userId"),
        @Index(name = "idx_ph_stripe_pi", columnList = "stripePaymentIntentId")
})
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** 關聯的訂閱 ID (nullable) */
    private Long subscriptionId;

    /** Stripe PaymentIntent ID (pi_xxx) */
    private String stripePaymentIntentId;

    /** 金額 */
    private Double amount;

    /** 幣別 */
    @Builder.Default
    private String currency = "USD";

    /** 狀態: succeeded / failed / refunded */
    private String status;

    /** 實際付款時間 */
    private LocalDateTime paidAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
