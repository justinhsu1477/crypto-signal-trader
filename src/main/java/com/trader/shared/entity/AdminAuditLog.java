package com.trader.shared.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin 操作稽核日誌。
 *
 * <p>記錄 admin 對「物件」的修改（高風險操作專用），與
 * {@link AuditLog}（記錄一般用戶的登入/瀏覽行為）分離。
 *
 * <p>不存全文，只存 SHA-256 前 16 hex；要查全文時從版本表（如
 * {@code signal_source_prompt_versions}）對 hash 查回去。
 */
@Entity
@Table(name = "admin_audit_log", indexes = {
        @Index(name = "idx_admin_audit_log_admin", columnList = "admin_user_id, created_at"),
        @Index(name = "idx_admin_audit_log_target", columnList = "target_type, target_id, created_at"),
        @Index(name = "idx_admin_audit_log_action", columnList = "action, created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", length = 36, nullable = false)
    private String adminUserId;

    /** 操作類型，建議用 {@link Action} 常數 */
    @Column(name = "action", length = 64, nullable = false)
    private String action;

    /** 目標物件類型，建議用 {@link TargetType} 常數 */
    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, nullable = false)
    private String targetId;

    /** 改動前的 SHA-256 前 16 hex（建立時可為 null） */
    @Column(name = "before_hash", length = 16)
    private String beforeHash;

    /** 改動後的 SHA-256 前 16 hex（刪除時可為 null） */
    @Column(name = "after_hash", length = 16)
    private String afterHash;

    /** TEMPORARY negative-test field — 故意對應 DB 不存在的欄位，驗證 schema test 會抓 missing column。
     *  此欄位 PR 不會 merge，會被砍掉。*/
    @Column(name = "totally_fake_column_for_schema_test", nullable = false)
    private String totallyFakeColumnForSchemaTest;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    /** 高風險操作的標準動作字串。新增類別記得同步 docs/admin-permission-model.md。 */
    public static final class Action {
        public static final String UPDATE_CUSTOM_PROMPT = "UPDATE_CUSTOM_PROMPT";
        public static final String UPDATE_MIRROR_WEBHOOK = "UPDATE_MIRROR_WEBHOOK";
        public static final String UPDATE_TRADE_MODE = "UPDATE_TRADE_MODE";
        public static final String DELETE_SIGNAL_SOURCE = "DELETE_SIGNAL_SOURCE";
        public static final String CREATE_SIGNAL_SOURCE = "CREATE_SIGNAL_SOURCE";

        private Action() {}
    }

    public static final class TargetType {
        public static final String SIGNAL_SOURCE = "SIGNAL_SOURCE";
        public static final String USER = "USER";
        public static final String SUBSCRIPTION = "SUBSCRIPTION";

        private TargetType() {}
    }
}
