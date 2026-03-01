package com.trader.auth.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Email OTP 驗證碼
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    // 儲存 OTP hash（為了向後相容，舊資料可能仍是 6 位數明文）
    @Column(nullable = false, length = 128)
    private String code;

    @Builder.Default
    private int attempts = 0;

    @Builder.Default
    private boolean used = false;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
