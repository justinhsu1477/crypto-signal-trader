package com.trader.trading.dto.signalsource;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin 設定 mirror webhook 的 request body。
 *
 * <ul>
 *     <li>{@code webhookUrl} null/空白 → 清除設定 + 強制 enabled=false</li>
 *     <li>{@code enabled} 控制 mirror 開關</li>
 *     <li>{@code reason} admin 必填，寫進 admin_audit_log</li>
 * </ul>
 *
 * <p>明碼 URL 進來，service 端會 AES 加密入庫；audit log 只記 fingerprint 不存全文。
 */
@Data
@NoArgsConstructor
public class UpdateMirrorWebhookRequest {
    /** Discord webhook URL — 明碼。Service 端會 AES 加密入庫。null/blank = 清除設定 */
    private String webhookUrl;

    /** mirror 是否啟用。clearWebhook 時 service 端會自動強制 false */
    private boolean enabled;

    /** admin 修改原因，寫進 admin_audit_log */
    private String reason;
}
