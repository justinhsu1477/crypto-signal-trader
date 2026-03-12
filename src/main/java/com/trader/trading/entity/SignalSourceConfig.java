package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 訊號來源設定 — 對應一個 Discord 群組/頻道
 *
 * 命名為 SignalSourceConfig 避免與 com.trader.shared.model.SignalSource（DTO）衝突
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "signal_sources", indexes = {
    @Index(name = "idx_ss_enabled", columnList = "enabled")
})
public class SignalSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Admin 內部名稱（如「陳哥VIP群」），用戶不可見 */
    private String name;

    /** 用戶看到的別名（如「訊號源 A」） */
    private String displayName;

    /** Discord channel ID */
    private String channelId;

    /** Discord guild ID */
    private String guildId;

    /** Admin 備註 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 啟用狀態 */
    @Builder.Default
    private boolean enabled = true;

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
