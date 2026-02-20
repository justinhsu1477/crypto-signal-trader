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
@Table(name = "user_discord_webhooks", indexes = {
        @Index(name = "idx_udw_user_id", columnList = "user_id")
})
public class UserDiscordWebhook {

    @Id
    private String webhookId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String webhookUrl;

    @Builder.Default
    private boolean enabled = true;

    private String name;

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
