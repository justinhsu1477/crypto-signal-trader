package com.trader.referral.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用戶交易所推薦綁定記錄
 *
 * Single Source of Truth：所有「是否已驗證推薦碼」的判斷
 * 都查這張表的 status 欄位，不在 User 表加冗餘欄位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_exchange_referral_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_uerl_user_exchange",
                        columnNames = {"userId", "exchange"})
        },
        indexes = {
                @Index(name = "idx_uerl_status", columnList = "status"),
                @Index(name = "idx_uerl_user", columnList = "userId")
        })
public class UserExchangeReferralLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String exchange = "BINANCE";

    @Column(length = 64)
    private String exchangeUid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ReferralStatus status = ReferralStatus.NOT_STARTED;

    private LocalDateTime verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String adminNotes;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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
