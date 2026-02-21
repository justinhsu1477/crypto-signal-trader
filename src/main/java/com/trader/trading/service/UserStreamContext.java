package com.trader.trading.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.WebSocket;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每個用戶的 User Data Stream 狀態容器
 *
 * 封裝一個用戶的 listenKey、WebSocket 連線、重連狀態。
 * 由 MultiUserDataStreamManager 管理生命週期。
 */
@Slf4j
public class UserStreamContext {

    // 用戶身份（不可變）
    @Getter
    private final String userId;
    @Getter
    private final Instant createdAt;

    // API 憑證（可更新 — 用戶可能在重連期間換了 key）
    @Getter
    private volatile String apiKey;
    @Getter
    private volatile String secretKey;

    // 連線狀態（可變）
    private volatile String listenKey;
    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    private volatile boolean selfInitiatedClose = false;
    private volatile boolean alertSent = false;

    // 重連狀態
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>(null);
    private volatile ScheduledFuture<?> pendingReconnect;

    public UserStreamContext(String userId, String apiKey, String secretKey) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.createdAt = Instant.now();
    }

    /**
     * 更新 API 憑證（重連時取到新 key 用）
     */
    public void updateApiKey(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    // ==================== listenKey & WebSocket ====================

    public String getListenKey() {
        return listenKey;
    }

    public void setListenKey(String listenKey) {
        this.listenKey = listenKey;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    // ==================== 連線狀態 ====================

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isSelfInitiatedClose() {
        return selfInitiatedClose;
    }

    public void setSelfInitiatedClose(boolean selfInitiatedClose) {
        this.selfInitiatedClose = selfInitiatedClose;
    }

    public boolean isAlertSent() {
        return alertSent;
    }

    public void setAlertSent(boolean alertSent) {
        this.alertSent = alertSent;
    }

    // ==================== 訊息時間 ====================

    public Instant getLastMessageTime() {
        return lastMessageTime.get();
    }

    public void updateLastMessageTime() {
        lastMessageTime.set(Instant.now());
    }

    // ==================== 重連狀態 ====================

    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    public int incrementReconnectAttempts() {
        return reconnectAttempts.incrementAndGet();
    }

    public void resetReconnectAttempts() {
        reconnectAttempts.set(0);
    }

    public ScheduledFuture<?> getPendingReconnect() {
        return pendingReconnect;
    }

    public void setPendingReconnect(ScheduledFuture<?> future) {
        this.pendingReconnect = future;
    }

    /**
     * 取消當前排程中的重連任務
     */
    public void cancelPendingReconnect() {
        ScheduledFuture<?> pending = this.pendingReconnect;
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
    }

    /**
     * 重置所有重連狀態（連線成功時呼叫）
     */
    public void resetOnConnected() {
        connected = true;
        selfInitiatedClose = false;
        reconnectAttempts.set(0);
        lastMessageTime.set(Instant.now());
    }

    // ==================== 狀態查詢 ====================

    public Map<String, Object> getStatus() {
        Instant lastMsg = lastMessageTime.get();
        long elapsed = lastMsg != null ? Instant.now().getEpochSecond() - lastMsg.getEpochSecond() : -1;
        return Map.of(
                "userId", userId,
                "connected", connected,
                "listenKeyActive", listenKey != null,
                "lastMessageTime", lastMsg != null ? lastMsg.toString() : "never",
                "elapsedSeconds", elapsed,
                "reconnectAttempts", reconnectAttempts.get(),
                "alertSent", alertSent
        );
    }

    @Override
    public String toString() {
        return String.format("UserStreamContext{userId='%s', connected=%s, attempts=%d}",
                userId, connected, reconnectAttempts.get());
    }
}
