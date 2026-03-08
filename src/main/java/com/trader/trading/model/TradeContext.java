package com.trader.trading.model;

/**
 * 交易上下文 — 顯式傳遞取代 ThreadLocal 隱式依賴
 *
 * 取代以下 ThreadLocal：
 * - TradeRecordService.CURRENT_USER_ID → userId
 * - TradeRecordService.CURRENT_USER_DISPLAY_NAME → displayName
 * - TradingOrchestrator.BROADCAST_CONTEXT → broadcastMode
 *
 * record 是不可變的，天生 thread-safe。
 */
public record TradeContext(
        String userId,
        String displayName,
        boolean broadcastMode
) {
    /** 單用戶 HTTP 請求（JWT userId） */
    public static TradeContext fromRequest(String userId) {
        return new TradeContext(userId, null, false);
    }

    /** 廣播跟單：完整上下文 */
    public static TradeContext forBroadcast(String userId, String displayName) {
        return new TradeContext(userId, displayName, true);
    }

    /** 排程任務：有 userId 但非廣播 */
    public static TradeContext forScheduledTask(String userId) {
        return new TradeContext(userId, null, false);
    }

    /** WebSocket 事件：有 userId，非廣播 */
    public static TradeContext forWebSocket(String userId) {
        return new TradeContext(userId, null, false);
    }

    /** displayName 有值就用，否則 fallback 到 userId */
    public String effectiveDisplayName() {
        return (displayName != null && !displayName.isBlank()) ? displayName : userId;
    }
}
