package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一個 signal source 額外對應的 Discord mirror webhook target。
 *
 * <p>交易路由仍只看 {@link SignalSourceConfig}；這張表只控制 admin mirror fan-out。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "signal_source_mirror_targets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ssmt_source_target_channel",
                        columnNames = {"source_id", "target_channel_id"})
        },
        indexes = {
                @Index(name = "idx_ssmt_source_enabled", columnList = "source_id, enabled")
        }
)
public class SignalSourceMirrorTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private SignalSourceConfig source;

    @Column(name = "source_id", insertable = false, updatable = false)
    private Long sourceId;

    @Column(name = "target_guild_id", length = 64)
    private String targetGuildId;

    @Column(name = "target_channel_id", nullable = false, length = 64)
    private String targetChannelId;

    @Column(length = 100)
    private String label;

    @Column(name = "mirror_webhook_url", nullable = false, length = 512)
    private String mirrorWebhookUrl;

    @Column(nullable = false)
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
