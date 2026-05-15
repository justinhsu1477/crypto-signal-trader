package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新 SignalSource.customPrompt 的請求 DTO。
 *
 * <p>customPrompt 是 high-risk 欄位（影響該源所有訂閱用戶的 AI 解析），
 * 跟一般欄位的 update 端點分離，避免無意觸發。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCustomPromptRequest {

    /** 新的 prompt 內容；空字串或 null = 清空 */
    private String customPrompt;

    /** 修改理由（會寫進 admin_audit_log，建議填寫） */
    private String reason;
}
