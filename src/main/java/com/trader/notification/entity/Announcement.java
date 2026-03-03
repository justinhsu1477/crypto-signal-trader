package com.trader.notification.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 公告實體
 *
 * 生命週期：DRAFT → PUBLISHED → ARCHIVED
 * Admin 建立草稿，發佈後透過 RabbitMQ Fanout Exchange 推送至 Discord / LINE / WebSocket。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "announcements", indexes = {
        @Index(name = "idx_ann_status", columnList = "status"),
        @Index(name = "idx_ann_published_at", columnList = "publishedAt")
})
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Category category = Category.GENERAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Priority priority = Priority.NORMAL;

    /** 推送頻道：ALL 或逗號分隔，如 DISCORD,LINE,WEBSOCKET */
    @Column(nullable = false, length = 100)
    @Builder.Default
    private String channels = "ALL";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private Status status = Status.DRAFT;

    private LocalDateTime publishedAt;

    @Column(nullable = false, length = 36)
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 列舉 =====

    public enum Category {
        GENERAL,        // 一般公告
        MAINTENANCE,    // 系統維護
        UPDATE,         // 功能更新
        URGENT,         // 緊急通知
        PROMOTION       // 活動推廣
    }

    public enum Priority {
        LOW, NORMAL, HIGH, CRITICAL
    }

    public enum Status {
        DRAFT, PUBLISHED, ARCHIVED
    }

    // ===== Lifecycle =====

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
