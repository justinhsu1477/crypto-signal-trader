package com.trader.dashboard.controller;

import com.trader.dashboard.dto.AdminSendNotificationRequest;
import com.trader.notification.service.NotificationService;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin 針對特定用戶發送通知 API
 *
 * 路徑 /api/admin/notifications → 受 AuthConfig hasRole("ADMIN") 保護。
 * 復用 NotificationService.sendNotificationToUser() 發送 Discord + LINE 通知。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * 發送通知給指定用戶
     *
     * @param request 包含 userIds、title、message、color
     * @return 發送結果統計
     */
    @PostMapping("/send")
    public ResponseEntity<?> send(@Valid @RequestBody AdminSendNotificationRequest request) {
        String adminId = SecurityUtil.getCurrentUserId();
        List<String> validUserIds = new ArrayList<>();
        List<String> invalidUserIds = new ArrayList<>();

        // 驗證 userIds 存在
        for (String userId : request.getUserIds()) {
            if (userRepository.existsById(userId)) {
                validUserIds.add(userId);
            } else {
                invalidUserIds.add(userId);
            }
        }

        if (validUserIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "所有用戶 ID 無效",
                    "invalidUserIds", invalidUserIds
            ));
        }

        int color = parseColor(request.getColor());
        String notifTitle = "📢 " + request.getTitle();

        int successCount = 0;
        int failCount = 0;

        for (String userId : validUserIds) {
            try {
                notificationService.sendNotificationToUser(userId, notifTitle, request.getMessage(), color);
                successCount++;
            } catch (Exception e) {
                log.error("發送通知給用戶 {} 失敗: {}", userId, e.getMessage());
                failCount++;
            }
        }

        auditService.log(adminId, "SEND_NOTIFICATION", "/api/admin/notifications/send",
                "SUCCESS", "",
                String.format("targets=%d success=%d fail=%d title=%s",
                        validUserIds.size(), successCount, failCount, request.getTitle()));

        return ResponseEntity.ok(Map.of(
                "message", "通知發送完成",
                "totalUsers", validUserIds.size(),
                "successCount", successCount,
                "failCount", failCount,
                "invalidUserIds", invalidUserIds
        ));
    }

    /**
     * 解析顏色字串為 int 色碼
     */
    private int parseColor(String color) {
        if (color == null || color.isBlank()) {
            return NotificationService.COLOR_BLUE;
        }
        return switch (color.toUpperCase()) {
            case "GREEN" -> NotificationService.COLOR_GREEN;
            case "RED" -> NotificationService.COLOR_RED;
            case "YELLOW" -> NotificationService.COLOR_YELLOW;
            case "BLUE" -> NotificationService.COLOR_BLUE;
            default -> NotificationService.COLOR_BLUE;
        };
    }
}
