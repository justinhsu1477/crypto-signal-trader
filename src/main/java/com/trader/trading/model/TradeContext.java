package com.trader.trading.model;

import com.trader.trading.service.BinanceFuturesService;
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
 * @param apiKey        Binance API key — Issue #52 Phase 1：multi-user WebSocket
 *                       入口點要把 key 帶進 ThreadLocal，
 *                       否則 cancelSLTPOrdersIfPositionClosed 之類用錯帳戶查倉位 / 取消單。
 *                       既有 forBroadcast / forScheduledTask 入口點 keys 通常已單獨設過
 *                       （executeSignalForBroadcast 等），傳 null 即可。
 * @param secretKey     Binance secret key — 同上
 */
public record TradeContext(
        String userId,
        String displayName,
        boolean broadcastMode,
        String apiKey,
        String secretKey
) {

    /** 廣播跟單：含用戶顯示名稱（keys 由 executeSignalForBroadcast 另外設）*/
    public static TradeContext forBroadcast(String userId, String displayName) {
        return new TradeContext(userId, displayName, true, null, null);
    }

    /** 排程任務：不含顯示名稱（keys 各 task 內部自己 set）*/
    public static TradeContext forScheduledTask(String userId) {
        return new TradeContext(userId, null, false, null, null);
    }

    /**
     * WebSocket 事件（legacy，無 keys）
     * 注意：multi-user 模式呼叫者請改用 {@link #forWebSocket(String, String, String)} 帶 keys；
     * 否則 cancelSLTPOrdersIfPositionClosed 之類會用全域 key 查倉位 → 看到錯帳戶 → 誤判。
     */
    public static TradeContext forWebSocket(String userId) {
        return new TradeContext(userId, null, false, null, null);
    }

    /**
     * WebSocket 事件 — multi-user 專用，帶 per-user Binance keys（Issue #52 Phase 1）。
     * MultiUserDataStreamManager.onMessage 必須用這個版本。
     */
    public static TradeContext forWebSocket(String userId, String apiKey, String secretKey) {
        return new TradeContext(userId, null, false, apiKey, secretKey);
    }

    /** 取得有效顯示名稱（displayName 為空時 fallback 到 userId） */
    public String effectiveDisplayName() {
        return (displayName != null && !displayName.isBlank()) ? displayName : userId;
    }

    /**
     * Bridge: 將 context 設入 ThreadLocal（Phase 1 過渡用）
     * 必須搭配 finally { clearThreadLocals() } 使用
     *
     * - userId / displayName → TradeRecordService ThreadLocal
     * - apiKey / secretKey   → BinanceFuturesService.CURRENT_USER_KEYS（兩個都非空才設）
     */
    public void installThreadLocals() {
        TradeRecordService.setCurrentUserId(userId);
        if (displayName != null) {
            TradeRecordService.setCurrentUserDisplayName(displayName);
        }
        BinanceFuturesService.setCurrentUserKeys(apiKey, secretKey);
    }

    /**
     * Bridge: 集中清除所有 TradeContext 相關的 ThreadLocal
     * 避免散落各處的個別 clear 呼叫遺漏
     *
     * 連 Binance keys 一起清 — 即使本 context 沒設 keys（forBroadcast 等），
     * 也要清掉前一次操作殘留，避免線程池復用洩漏。
     */
    public static void clearThreadLocals() {
        TradeRecordService.clearCurrentUserId();
        TradeRecordService.clearCurrentUserDisplayName();
        BinanceFuturesService.clearCurrentUserKeys();
    }
}
