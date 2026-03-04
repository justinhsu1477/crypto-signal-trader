package com.trader.auth.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * OAuth CSRF state 暫存
 *
 * 每次 OAuth 請求生成隨機 state，存入 DB，callback 時驗證。
 * 10 分鐘過期，一次性使用。定期清理由 OAuthService 排程處理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "oauth_states",
       indexes = @Index(name = "idx_os_expires", columnList = "expires_at"))
public class OAuthState {

    @Id
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OAuthProviderType provider;

    @Column(name = "code_verifier", length = 128)
    private String codeVerifier;

    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    public boolean isExpired() {
        return LocalDateTime.now(AppConstants.ZONE_ID).isAfter(expiresAt);
    }
}
