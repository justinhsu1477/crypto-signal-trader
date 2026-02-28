package com.trader.user.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_line_bindings")
public class UserLineBinding {

    @Id
    private String userId;

    @Column(nullable = false, unique = true)
    private String lineUserId;

    private String displayName;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime linkedAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        linkedAt = LocalDateTime.now(AppConstants.ZONE_ID);
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
