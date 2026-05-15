package com.trader.shared.service;

import com.trader.shared.entity.AuditLog;
import com.trader.shared.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 審計服務 — 記錄用戶層級操作（LOGIN / LOGOUT / VIEW 等）。
 *
 * <p>跟 {@link AdminAuditService}（admin 對物件的修改）分離：
 * <ul>
 *   <li>{@code AuditService}：誰登入了、誰看了什麼 — 認證 / 行為層</li>
 *   <li>{@code AdminAuditService}：誰改了 signal_source / 訂閱 — 物件層</li>
 * </ul>
 *
 * <p>所有寫入都是非同步（跑在 {@code auditExecutor}），不會擋業務。
 */
@Service
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 自身的 Spring proxy（{@code @Lazy} 避免 bean 建立循環）。
     *
     * <p>用來避免 self-invocation 陷阱：在同一個 bean 內 {@code this.log()} 不會走 proxy，
     * {@code @Async} 和 {@code @Transactional} 都會失效。透過 {@code self.log()} 才會
     * 經 proxy 觸發 advice。
     */
    private final AuditService self;

    public AuditService(AuditLogRepository auditLogRepository,
                        @Lazy AuditService self) {
        this.auditLogRepository = auditLogRepository;
        this.self = self;
    }

    /**
     * 寫入一筆審計日誌 —— 異步且有獨立 transaction。
     *
     * <p>外部呼叫直接打這個；類內呼叫請走 {@code self.log()}。
     */
    @Async("auditExecutor")
    @Transactional
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

    /** 登入 — 透過 self proxy 委派，異步生效。 */
    public void logLogin(String userId, String resource, String status, String ipAddress) {
        self.log(userId, "LOGIN", resource, status, ipAddress, "");
    }

    /** 登出 */
    public void logLogout(String userId, String ipAddress) {
        self.log(userId, "LOGOUT", "/api/auth/logout", "SUCCESS", ipAddress, "");
    }

    /** 認證失敗（防暴力破解用） */
    public void logFailedAuth(String email, String ipAddress, String reason) {
        self.log(null, "LOGIN", "/api/auth/login", "FAILED", ipAddress,
                "Email: " + email + ", Reason: " + reason);
    }
}
