package com.trader.notification.controller;

import com.trader.notification.dto.AnnouncementListResponse;
import com.trader.notification.service.AnnouncementService;
import com.trader.shared.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 公告 API（一般用戶）
 *
 * 路徑 /api/announcements → 需要認證（AuthConfig: authenticated）。
 *
 * 功能：
 * - 查看已發佈公告（分頁）
 * - 標記已讀
 * - 取得未讀數量
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 已發佈公告列表（分頁 + 已讀狀態） */
    @GetMapping
    public ResponseEntity<AnnouncementListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(announcementService.getPublishedForUser(userId, page, size));
    }

    /** 標記已讀 */
    @PostMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@PathVariable Long id) {
        String userId = SecurityUtil.getCurrentUserId();
        announcementService.markAsRead(id, userId);
        return ResponseEntity.ok(Map.of("message", "已標記為已讀"));
    }

    /** 未讀數量 */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(Map.of("count", announcementService.getUnreadCount(userId)));
    }
}
