package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用戶-訊號來源綁定 — 多對多（MVP 階段 Service 層限制一對一）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_signal_sources")
public class UserSignalSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用戶 ID */
    private String userId;

    /** 訊號來源 ID */
    private Long sourceId;

    /** 綁定啟用狀態 */
    @Builder.Default
    private boolean enabled = true;

    /** 綁定時間 */
    private LocalDateTime assignedAt;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
