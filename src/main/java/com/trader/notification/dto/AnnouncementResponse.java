package com.trader.notification.dto;

import com.trader.notification.entity.Announcement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 公告回應 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementResponse {

    private Long id;
    private String title;
    private String content;
    private String category;
    private String priority;
    private String channels;
    private String imageUrl;
    private String status;
    private LocalDateTime publishedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 已讀人數（Admin 用） */
    private Long readCount;

    /** 當前用戶是否已讀（User 用） */
    private Boolean isRead;

    public static AnnouncementResponse from(Announcement a) {
        return AnnouncementResponse.builder()
                .id(a.getId())
                .title(a.getTitle())
                .content(a.getContent())
                .category(a.getCategory().name())
                .priority(a.getPriority().name())
                .channels(a.getChannels())
                .imageUrl(a.getImageUrl())
                .status(a.getStatus().name())
                .publishedAt(a.getPublishedAt())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
