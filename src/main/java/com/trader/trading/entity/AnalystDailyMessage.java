package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分析師每日訊息 — 每位分析師每天一筆，append 累積所有訊息內容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "analyst_daily_messages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"analyst_name", "message_date"}),
        indexes = {
                @Index(name = "idx_adm_date", columnList = "message_date"),
                @Index(name = "idx_adm_channel", columnList = "channel_id")
        })
public class AnalystDailyMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analyst_name", nullable = false, length = 100)
    private String analystName;

    @Column(name = "channel_id", nullable = false, length = 50)
    private String channelId;

    @Column(name = "message_date", nullable = false)
    private LocalDate messageDate;

    @Column(columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String content = "";

    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private int messageCount = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
