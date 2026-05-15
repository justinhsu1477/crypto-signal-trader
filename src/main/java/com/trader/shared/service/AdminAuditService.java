package com.trader.shared.service;

import com.trader.shared.entity.AdminAuditLog;
import com.trader.shared.repository.AdminAuditLogRepository;
import com.trader.shared.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 寫 admin 稽核日誌的入口。
 *
 * <p>使用方式：在 Service 層完成「物件被修改」後呼叫 {@link #record}。
 * 若呼叫時還在 tx 中，會註冊 AFTER_COMMIT hook — tx rollback 時 audit 不寫，
 * 避免「audit 說改了但 DB 沒改」的偽陽性。
 *
 * <p>寫入本身失敗不擋主流程（只記 warn log），但只在 tx commit 後才嘗試寫入。
 */
@Slf4j
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;
    /** @Lazy 自我注入：透過 proxy 呼叫 persistAsync 才會走 @Async；this.x() 是 self-invocation 不會生效。 */
    private final AdminAuditService self;

    public AdminAuditService(AdminAuditLogRepository repository,
                             @Lazy AdminAuditService self) {
        this.repository = repository;
        this.self = self;
    }

    /**
     * 排程一筆稽核紀錄。
     *
     * <p>若呼叫端還在 @Transactional 區塊內 → 註冊 AFTER_COMMIT hook，
     * tx rollback 時直接 drop。若沒在 tx 內（後台排程觸發）→ 直接 async 寫。
     *
     * <p>{@code adminUserId} 在這裡解析，因為 AFTER_COMMIT 回呼可能脫離 Security Context。
     *
     * @param action     操作類型（見 {@link AdminAuditLog.Action}）
     * @param targetType 物件類型（見 {@link AdminAuditLog.TargetType}）
     * @param targetId   物件 ID（字串化）
     * @param beforeValue 改動前的完整內容（可為 null）；本方法只算 hash 不存全文
     * @param afterValue  改動後的完整內容（可為 null）
     * @param reason      admin 提供的修改理由（可為 null）
     * @param ipAddress   呼叫端 IP（可為 null）
     */
    public void record(String action, String targetType, String targetId,
                       String beforeValue, String afterValue,
                       String reason, String ipAddress) {
        // 立即抓所有需要的狀態 — 之後可能離開 tx / security context
        final String adminUserId = resolveAdminId();
        final String beforeHash = hashOrNull(beforeValue);
        final String afterHash = hashOrNull(afterValue);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.persistAsync(action, targetType, targetId, beforeHash, afterHash,
                            reason, ipAddress, adminUserId);
                }
            });
        } else {
            // 沒在 tx 內 — 後台排程 / 系統觸發，走 proxy 才會被 @Async 攔
            self.persistAsync(action, targetType, targetId, beforeHash, afterHash,
                    reason, ipAddress, adminUserId);
        }
    }

    /**
     * 實際寫入 audit log。AFTER_COMMIT 回呼或直接呼叫時觸發。
     * 失敗只記 warn log（此時 tx 已 commit，再 raise 也無意義）。
     */
    @Async("auditExecutor")
    public void persistAsync(String action, String targetType, String targetId,
                             String beforeHash, String afterHash,
                             String reason, String ipAddress, String adminUserId) {
        try {
            AdminAuditLog entry = AdminAuditLog.builder()
                    .adminUserId(adminUserId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .beforeHash(beforeHash)
                    .afterHash(afterHash)
                    .reason(reason)
                    .ipAddress(ipAddress)
                    .build();
            repository.save(entry);
            log.info("admin audit: {} target={}/{} by={} reason={}",
                    action, targetType, targetId, adminUserId, reason);
        } catch (Exception e) {
            log.warn("admin audit 寫入失敗（不影響主流程）: action={} target={}/{} err={}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    /**
     * 算 SHA-256 並取前 16 hex（足夠對齊 BroadcastLog 的稽核鏈，又不暴露全文）。
     */
    public static String hashOrNull(String value) {
        if (value == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 SHA-256，不會到這裡
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 解析當前 admin user ID。沒 Security Context 時 fallback "system" 並 warn log，
     * 這樣後台排程是合法觸發，但若 admin endpoint 走到這條 path 也會在 alarm 上看到。
     */
    private String resolveAdminId() {
        try {
            return SecurityUtil.getCurrentUserId();
        } catch (IllegalStateException e) {
            log.warn("admin audit fallback to 'system' — no auth context "
                    + "(scheduled task OK; admin endpoint hitting this is BUG): {}", e.getMessage());
            return "system";
        }
    }
}
