package com.trader.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_api_keys", indexes = {
        @Index(name = "idx_api_key_user_id", columnList = "userId")
})
public class UserApiKey {

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

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
        createdAt = LocalDateTime.now(TAIPEI_ZONE);
        updatedAt = LocalDateTime.now(TAIPEI_ZONE);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(TAIPEI_ZONE);
    }
}
