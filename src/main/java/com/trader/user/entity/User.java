package com.trader.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import com.trader.shared.config.AppConstants;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true)
})
public class User {

    @Id
    private String userId;

    @Column(unique = true)
    private String email;

    @JsonIgnore
    private String passwordHash;

    private String name;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Role role = Role.USER;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private boolean emailVerified = false;

    @Builder.Default
    private boolean autoTradeEnabled = false;

    @Builder.Default
    private boolean discordNotificationEnabled = true;

    @Builder.Default
    private boolean lineNotificationEnabled = true;

    private LocalDateTime passwordChangedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Role {
        USER, ADMIN
    }

    /**
     * 是否有設定密碼（OAuth-only 用戶無密碼）
     */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
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
