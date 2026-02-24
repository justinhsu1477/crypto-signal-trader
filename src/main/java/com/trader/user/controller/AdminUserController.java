package com.trader.user.controller;

import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.dto.AdminUpdateUserRequest;
import com.trader.user.dto.AdminUserListResponse;
import com.trader.user.dto.AdminUserListResponse.AdminUserSummary;
import com.trader.user.dto.TradeSettingsResponse;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserTradeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理員用戶管理 API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final UserTradeSettingsService tradeSettingsService;
    private final AuditService auditService;

    /**
     * 列出所有用戶
     */
    @GetMapping
    public ResponseEntity<AdminUserListResponse> listUsers() {
        List<User> allUsers = userRepository.findAll();

        List<AdminUserSummary> summaries = allUsers.stream()
                .map(this::toSummary)
                .toList();

        long activeCount = allUsers.stream().filter(User::isEnabled).count();
        long adminCount = allUsers.stream()
                .filter(u -> u.getRole() == User.Role.ADMIN).count();

        return ResponseEntity.ok(AdminUserListResponse.builder()
                .users(summaries)
                .totalUsers(allUsers.size())
                .activeUsers(activeCount)
                .adminUsers(adminCount)
                .build());
    }

    /**
     * 取得單一用戶詳情（含交易設定）
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetail(@PathVariable String userId) {
        return userRepository.findById(userId)
                .map(user -> {
                    TradeSettingsResponse settings = tradeSettingsService
                            .toResponse(tradeSettingsService.getOrCreateSettings(userId));
                    return ResponseEntity.ok(Map.of(
                            "user", toSummary(user),
                            "tradeSettings", settings
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 更新用戶屬性（啟停帳號、切換角色）
     */
    @PutMapping("/{userId}")
    public ResponseEntity<?> updateUser(
            @PathVariable String userId,
            @RequestBody AdminUpdateUserRequest request) {

        String adminId = SecurityUtil.getCurrentUserId();

        return userRepository.findById(userId)
                .map(user -> {
                    // 安全護欄：不可停用自己
                    if (request.getEnabled() != null && !request.getEnabled()
                            && adminId.equals(userId)) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "不可停用自己的帳號"));
                    }

                    // 安全護欄：不可降級最後一個 ADMIN
                    if (request.getRole() != null
                            && "USER".equals(request.getRole())
                            && user.getRole() == User.Role.ADMIN
                            && userRepository.countByRole(User.Role.ADMIN) <= 1) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "系統至少需要一個管理員帳號"));
                    }

                    // 套用變更
                    if (request.getEnabled() != null) {
                        user.setEnabled(request.getEnabled());
                    }
                    if (request.getAutoTradeEnabled() != null) {
                        user.setAutoTradeEnabled(request.getAutoTradeEnabled());
                    }
                    if (request.getRole() != null) {
                        try {
                            user.setRole(User.Role.valueOf(request.getRole()));
                        } catch (IllegalArgumentException e) {
                            return ResponseEntity.badRequest().body(
                                    Map.of("error", "無效角色: " + request.getRole()));
                        }
                    }

                    userRepository.save(user);
                    log.info("管理員 {} 更新用戶 {}: {}", adminId, userId, request);

                    auditService.log(adminId, "ADMIN_UPDATE_USER",
                            "/api/admin/users/" + userId, "SUCCESS", "", request.toString());

                    return ResponseEntity.ok(Map.of(
                            "message", "用戶已更新",
                            "user", toSummary(user)
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查看任意用戶交易設定
     */
    @GetMapping("/{userId}/trade-settings")
    public ResponseEntity<?> getUserTradeSettings(@PathVariable String userId) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        TradeSettingsResponse response = tradeSettingsService
                .toResponse(tradeSettingsService.getOrCreateSettings(userId));
        return ResponseEntity.ok(response);
    }

    /**
     * 修改任意用戶交易設定
     */
    @PutMapping("/{userId}/trade-settings")
    public ResponseEntity<?> updateUserTradeSettings(
            @PathVariable String userId,
            @RequestBody UpdateTradeSettingsRequest request) {

        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }

        String adminId = SecurityUtil.getCurrentUserId();

        try {
            TradeSettingsResponse response = tradeSettingsService
                    .toResponse(tradeSettingsService.updateSettings(userId, request));

            log.info("管理員 {} 更新用戶 {} 交易設定", adminId, userId);
            auditService.log(adminId, "ADMIN_UPDATE_TRADE_SETTINGS",
                    "/api/admin/users/" + userId + "/trade-settings",
                    "SUCCESS", "", "");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== private helpers ====================

    private AdminUserSummary toSummary(User user) {
        return AdminUserSummary.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole().name())
                .enabled(user.isEnabled())
                .emailVerified(user.isEmailVerified())
                .autoTradeEnabled(user.isAutoTradeEnabled())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null)
                .build();
    }
}
