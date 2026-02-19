package com.trader.shared.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 審計日誌實體
 *
 * 記錄系統中的所有重要操作，用於：
 * 1. 安全審計（誰在何時從哪裡做了什麼）
 * 2. 問題追蹤（調試時查看發生了什麼）
 * 3. 合規性（金融系統要求的不可篡改日誌）
 *
 * 範例：
 * - userId=user123, action=LOGIN, status=SUCCESS, IP=192.168.1.1
 * - userId=user456, action=VIEW_DASHBOARD, status=SUCCESS, IP=::1
 * - userId=null, action=LOGIN, status=FAILED (invalid password), IP=192.168.1.100
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_action", columnList = "action"),
        @Index(name = "idx_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 操作的用戶 ID（可為 null，如登入失敗時）
     */
    @Column(name = "user_id", length = 36)
    private String userId;

    /**
     * 操作類型
     * - LOGIN: 登入
     * - LOGOUT: 登出
     * - VIEW_DASHBOARD: 查看儀表板
     * - EXECUTE_TRADE: 執行交易
     * - REFRESH_TOKEN: 刷新 token
     * - FAILED_AUTH: 認證失敗嘗試
     */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    /**
     * 操作的資源
     * - /api/dashboard/overview
     * - /api/auth/login
     * - /api/execute-trade
     */
    @Column(name = "resource", length = 200)
    private String resource;

    /**
     * 操作結果
     * - SUCCESS: 成功
     * - FAILED: 失敗
     */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /**
     * 客戶端 IP 地址
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * 操作時間（Asia/Taipei 時區）
     */
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /**
     * 詳細信息
     * - 失敗原因（如 "Invalid password", "Token expired"）
     * - 額外上下文（如交易對 BTCUSDT）
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
}
