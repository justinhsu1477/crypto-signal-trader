package com.trader.user.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用戶通知偏好（per-category 開關）
 *
 * 與 users 表 1:1（userId 為 PK），結構同 UserTradeSettings。
 * 無此列時由 Service 層 getOrCreate 建立預設值（全部啟用）。
 *
 * ER 關係：
 *   users.user_id (1) ←→ (1) user_notification_preferences.user_id
 *
 * 強制啟用分類（代碼層忽略此值，永遠發送）：
 *   - protectionLost：SL/TP 保護消失
 *   - systemAlert：Fail-Safe / 熔斷 / 啟動對帳
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_notification_preferences")
public class UserNotificationPreferences {

    /** PK = userId，一人一列 */
    @Id
    private String userId;

    /** 廣播跟單成功/失敗 */
    @Builder.Default
    private boolean tradeExecution = true;

    /** SL/TP 自動觸發 */
    @Builder.Default
    private boolean slTpTriggered = true;

    /** SL/TP 保護消失（代碼層強制啟用，此值僅供紀錄） */
    @Builder.Default
    private boolean protectionLost = true;

    /** 每日報表 + 殭屍清理 */
    @Builder.Default
    private boolean dailyReport = true;

    /** WebSocket 連線狀態 */
    @Builder.Default
    private boolean streamStatus = true;

    /** Fail-Safe / 熔斷 / 啟動對帳（代碼層強制啟用，此值僅供紀錄） */
    @Builder.Default
    private boolean systemAlert = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
