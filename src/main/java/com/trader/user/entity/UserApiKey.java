package com.trader.user.entity;

import jakarta.persistence.*;
import lombok.*;

import com.trader.shared.config.AppConstants;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_api_keys", indexes = {
        @Index(name = "idx_api_key_user_id", columnList = "userId")
})
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** 交易所名稱，例如 "BINANCE" */
    @Builder.Default
    private String exchange = "BINANCE";

    /** AES 加密後的 API Key */
    @Column(length = 512)
    private String encryptedApiKey;

    /** AES 加密後的 Secret Key */
    @Column(length = 512)
    private String encryptedSecretKey;

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
