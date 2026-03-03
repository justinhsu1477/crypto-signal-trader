package com.trader.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 公告列表回應（分頁 + 未讀數量）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementListResponse {

    private List<AnnouncementResponse> announcements;
    private int totalElements;
    private int totalPages;
    private int currentPage;
    private long unreadCount;
}
