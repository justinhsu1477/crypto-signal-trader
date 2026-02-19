package com.trader.shared.service;

import com.trader.shared.entity.AuditLog;
import com.trader.shared.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 審計服務
 *
 * 記錄系統中的重要操作。
 * 所有記錄都是非同步的，不會阻礙主業務邏輯。
 *
 * 使用範例：
 * - auditService.log(userId, "LOGIN", "/api/auth/login", "SUCCESS", "192.168.1.1", "");
 * - auditService.log(null, "LOGIN", "/api/auth/login", "FAILED", "192.168.1.100", "Invalid password");
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 記錄審計日誌（非同步）
     *
     * @param userId   操作用戶 ID（登入失敗時可為 null）
     * @param action   操作類型（LOGIN, LOGOUT, VIEW_DASHBOARD 等）
     * @param resource 操作資源（URI 路徑）
     * @param status   操作結果（SUCCESS 或 FAILED）
     * @param ipAddress 客戶端 IP
     * @param details  詳細信息
     */
    @Async
    public void log(String userId, String action, String resource,
                   String status, String ipAddress, String details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .userId(userId)
                    .action(action)
                    .resource(resource)
                    .status(status)
                    .ipAddress(ipAddress)
                    .timestamp(LocalDateTime.now(ZoneId.of("Asia/Taipei")))
                    .details(details)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("審計日誌記錄: action={} status={} userId={}", action, status, userId);
        } catch (Exception e) {
            // 審計失敗不應中斷主業務，但要記錄
            log.error("審計日誌記錄失敗", e);
        }
    }

    /**
     * 簡化版本：只需提供基本信息
     */
    @Async
    public void logLogin(String userId, String resource, String status, String ipAddress) {
        this.log(userId, "LOGIN", resource, status, ipAddress, "");
    }

    /**
     * 記錄登出
     */
    @Async
    public void logLogout(String userId, String ipAddress) {
        this.log(userId, "LOGOUT", "/api/auth/logout", "SUCCESS", ipAddress, "");
    }

    /**
     * 記錄認證失敗（用於防暴力破解）
     */
    @Async
    public void logFailedAuth(String email, String ipAddress, String reason) {
        this.log(null, "LOGIN", "/api/auth/login", "FAILED", ipAddress,
                "Email: " + email + ", Reason: " + reason);
    }
}
