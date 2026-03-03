package com.trader.notification.controller;

import com.trader.notification.dto.AnnouncementResponse;
import com.trader.notification.dto.CreateAnnouncementRequest;
import com.trader.notification.entity.Announcement;
import com.trader.notification.service.AnnouncementService;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公告管理 API（Admin 專用）
 *
 * 路徑 /api/admin/announcements → 受 AuthConfig hasRole("ADMIN") 保護。
 *
 * 生命週期：建立草稿 → 發佈 → 封存
 * 發佈時觸發多頻道推送（WebSocket + RabbitMQ → Discord / LINE）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;
    private final AuditService auditService;

    /** 列出所有公告（含草稿、已發佈、已封存） */
    @GetMapping
    public ResponseEntity<List<AnnouncementResponse>> list() {
        return ResponseEntity.ok(announcementService.getAllForAdmin());
    }

    /** 建立公告草稿 */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateAnnouncementRequest request) {
        String adminId = SecurityUtil.getCurrentUserId();

        try {
            Announcement announcement = announcementService.createDraft(adminId, request);
            auditService.log(adminId, "CREATE_ANNOUNCEMENT", "/api/admin/announcements",
                    "SUCCESS", "", "title=" + request.getTitle());
            return ResponseEntity.ok(AnnouncementResponse.from(announcement));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 更新公告草稿 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                     @Valid @RequestBody CreateAnnouncementRequest request) {
        String adminId = SecurityUtil.getCurrentUserId();

        try {
            Announcement announcement = announcementService.updateDraft(id, adminId, request);
            auditService.log(adminId, "UPDATE_ANNOUNCEMENT", "/api/admin/announcements/" + id,
                    "SUCCESS", "", "title=" + request.getTitle());
            return ResponseEntity.ok(AnnouncementResponse.from(announcement));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 發佈公告 → 觸發多頻道推送 */
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        String adminId = SecurityUtil.getCurrentUserId();

        try {
            Announcement announcement = announcementService.publish(id, adminId);
            auditService.log(adminId, "PUBLISH_ANNOUNCEMENT", "/api/admin/announcements/" + id + "/publish",
                    "SUCCESS", "", "title=" + announcement.getTitle() + " channels=" + announcement.getChannels());
            return ResponseEntity.ok(Map.of(
                    "message", "公告已發佈",
                    "announcement", AnnouncementResponse.from(announcement)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 封存公告 */
    @PutMapping("/{id}/archive")
    public ResponseEntity<?> archive(@PathVariable Long id) {
        String adminId = SecurityUtil.getCurrentUserId();

        try {
            announcementService.archive(id, adminId);
            auditService.log(adminId, "ARCHIVE_ANNOUNCEMENT", "/api/admin/announcements/" + id + "/archive",
                    "SUCCESS", "", "");
            return ResponseEntity.ok(Map.of("message", "公告已封存"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 刪除草稿 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String adminId = SecurityUtil.getCurrentUserId();

        try {
            announcementService.deleteDraft(id, adminId);
            auditService.log(adminId, "DELETE_ANNOUNCEMENT", "/api/admin/announcements/" + id,
                    "SUCCESS", "", "");
            return ResponseEntity.ok(Map.of("message", "草稿已刪除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
