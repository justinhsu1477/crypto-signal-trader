package com.trader.shared.service;

import com.trader.shared.entity.AdminAuditLog;
import com.trader.shared.repository.AdminAuditLogRepository;
import com.trader.shared.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 寫 admin 稽核日誌的入口。
 *
 * <p>使用方式：在 Service 層完成「物件被修改」後呼叫 {@link #record}。
 * 寫入失敗不會擋住主流程（只記 warn log），稽核紀錄是 best-effort 而非交易性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    /**
     * 寫一筆稽核紀錄。建議在主流程的 transaction 結束後呼叫，避免互相干擾。
     *
     * @param action     操作類型（見 {@link AdminAuditLog.Action}）
     * @param targetType 物件類型（見 {@link AdminAuditLog.TargetType}）
     * @param targetId   物件 ID（字串化）
     * @param beforeValue 改動前的完整內容（可為 null）；本方法只算 hash 不存全文
     * @param afterValue  改動後的完整內容（可為 null）
     * @param reason      admin 提供的修改理由（可為 null）
     * @param ipAddress   呼叫端 IP（可為 null）
     */
    @Async("auditExecutor")
    public void record(String action, String targetType, String targetId,
                       String beforeValue, String afterValue,
                       String reason, String ipAddress) {
        try {
            String adminUserId = resolveAdminId();
            AdminAuditLog entry = AdminAuditLog.builder()
                    .adminUserId(adminUserId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .beforeHash(hashOrNull(beforeValue))
                    .afterHash(hashOrNull(afterValue))
                    .reason(reason)
                    .ipAddress(ipAddress)
                    .build();
            repository.save(entry);
            log.info("admin audit: {} {} target={}/{} by={} reason={}",
                    action, targetType, targetType, targetId, adminUserId, reason);
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

    private String resolveAdminId() {
        try {
            return SecurityUtil.getCurrentUserId();
        } catch (IllegalStateException e) {
            // 後台排程 / 系統內部觸發時可能沒登入上下文
            return "system";
        }
    }
}
