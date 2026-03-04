package com.trader.auth.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * OAuth 第三方登入綁定
 *
 * 一個用戶可綁定多個提供者（LINE + Google + Discord）。
 * 一個提供者帳號只能綁定一個用戶。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_oauth_providers",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_oauth_provider_user",
           columnNames = {"provider", "provider_user_id"}
       ),
       indexes = @Index(name = "idx_uop_user_id", columnList = "user_id"))
public class UserOAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OAuthProviderType provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    private String displayName;

    private String email;

    @Column(name = "access_token", length = 1024)
    private String accessToken;

    @Column(name = "refresh_token", length = 1024)
    private String refreshToken;

    @Column(columnDefinition = "TEXT")
    private String metadata;

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
