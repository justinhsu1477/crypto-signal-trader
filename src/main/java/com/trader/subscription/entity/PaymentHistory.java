package com.trader.subscription.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
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

    /** Stripe PaymentIntent ID (pi_xxx) — 保留向下相容 */
    private String stripePaymentIntentId;

    /** 鏈上交易 Hash（USDT TRC20） */
    private String txHash;

    /** 區塊鏈網路（TRC20） */
    private String network;

    /** 收款錢包地址 */
    private String walletAddress;

    /** 金額 */
    @Column(columnDefinition = "DECIMAL(10,2)")
    private BigDecimal amount;

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
