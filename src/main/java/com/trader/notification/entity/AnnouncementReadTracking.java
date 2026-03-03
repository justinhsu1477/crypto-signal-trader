package com.trader.notification.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 公告已讀追蹤
 *
 * 記錄每位用戶對每則公告的已讀時間。
 * UNIQUE(announcement_id, user_id) 確保不重複標記。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "announcement_read_tracking",
        uniqueConstraints = @UniqueConstraint(columnNames = {"announcementId", "userId"}),
        indexes = {
                @Index(name = "idx_art_user_id", columnList = "userId")
        })
public class AnnouncementReadTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        readAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
