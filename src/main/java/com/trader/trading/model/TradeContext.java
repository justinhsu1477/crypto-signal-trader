package com.trader.trading.model;

import com.trader.trading.service.TradeRecordService;

/**
 * 交易執行上下文 — 取代隱式 ThreadLocal 傳遞
 *
 * Phase 1（Bridge 模式）：
 * - 各入口點建立 TradeContext，明確表達「這次操作是為了哪個用戶」
 * - installThreadLocals() 橋接至現有 ThreadLocal，內部業務邏輯不動
 * - clearThreadLocals() 集中清除，避免遺漏
 *
 * Phase 2（未來）：
 * - 將 TradeContext 作為參數穿透整個呼叫鏈
 * - 逐步移除 ThreadLocal 依賴
 *
 * @param userId        用戶 ID
 * @param displayName   用戶顯示名稱（格式：name (email)），通知用
 * @param broadcastMode 是否為廣播跟單模式
 */
public record TradeContext(
        String userId,
        String displayName,
        boolean broadcastMode
) {

    /** 廣播跟單：含用戶顯示名稱 */
    public static TradeContext forBroadcast(String userId, String displayName) {
        return new TradeContext(userId, displayName, true);
    }

    /** 排程任務：不含顯示名稱 */
    public static TradeContext forScheduledTask(String userId) {
        return new TradeContext(userId, null, false);
    }

    /** WebSocket 事件：不含顯示名稱 */
    public static TradeContext forWebSocket(String userId) {
        return new TradeContext(userId, null, false);
    }

    /** 取得有效顯示名稱（displayName 為空時 fallback 到 userId） */
    public String effectiveDisplayName() {
        return (displayName != null && !displayName.isBlank()) ? displayName : userId;
    }

    /**
     * Bridge: 將 context 設入 ThreadLocal（Phase 1 過渡用）
     * 必須搭配 finally { clearThreadLocals() } 使用
     */
    public void installThreadLocals() {
        TradeRecordService.setCurrentUserId(userId);
        if (displayName != null) {
            TradeRecordService.setCurrentUserDisplayName(displayName);
        }
    }

    /**
     * Bridge: 集中清除所有 TradeContext 相關的 ThreadLocal
     * 避免散落各處的個別 clear 呼叫遺漏
     */
    public static void clearThreadLocals() {
        TradeRecordService.clearCurrentUserId();
        TradeRecordService.clearCurrentUserDisplayName();
    }
}
