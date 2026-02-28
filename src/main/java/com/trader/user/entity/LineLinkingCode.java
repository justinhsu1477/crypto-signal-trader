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
@Table(name = "line_linking_codes")
public class LineLinkingCode {

    @Id
    private String code;

    @Column(nullable = false)
    private String userId;

    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    private boolean used = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    public boolean isExpired() {
        return LocalDateTime.now(AppConstants.ZONE_ID).isAfter(expiresAt);
    }
}
