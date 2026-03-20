package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.trading.model.TradeContext;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 多用戶 User Data Stream 管理器
 *
 * 當 multi-user.enabled=true 時，為每個啟用的用戶各開一條
 * 獨立的 WebSocket 連線，監聽 SL/TP 觸發、保護消失等事件。
 *
 * 設計：
 * - ConcurrentHashMap<userId, UserStreamContext> 管理所有用戶 stream
 * - 共用一個 ScheduledExecutorService 處理所有用戶的重連排程
 * - WebSocket Listener 在處理事件前設定 ThreadLocal userId，
 *   讓 TradeRecordService.resolveOpenTrade 自動走 per-user 查詢
 * - Discord 通知走 sendNotificationToUser(userId, ...) per-user webhook
 */
@Slf4j
@Component
public class MultiUserDataStreamManager {

    private final OkHttpClient httpClient;
    private final OkHttpClient wsClient;
    private final BinanceConfig binanceConfig;
    private final TradeRecordService tradeRecordService;
    private final NotificationService discordWebhookService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final UserApiKeyService userApiKeyService;
    private final UserRepository userRepository;
    private final Gson gson = new Gson();

    // 所有用戶的 stream 狀態
    private final ConcurrentHashMap<String, UserStreamContext> activeStreams = new ConcurrentHashMap<>();

    // 共用重連排程器（所有用戶共用，避免 per-user thread 浪費）
    // 使用 AtomicReference 確保 stopAllStreams → startAllStreams 可以重建 executor
    private final java.util.concurrent.atomic.AtomicReference<ScheduledExecutorService> reconnectExecutorRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    private volatile boolean shuttingDown = false;

    // 重連配置（與單用戶服務一致）
    static final long BASE_RECONNECT_DELAY_MS = 1000;
    static final long MAX_RECONNECT_DELAY_MS = 60_000;
    static final int MAX_RECONNECT_ATTEMPTS = 20;

    private final BinanceFuturesService binanceFuturesService;

    public MultiUserDataStreamManager(OkHttpClient httpClient,
                                       BinanceConfig binanceConfig,
                                       TradeRecordService tradeRecordService,
                                       NotificationService discordWebhookService,
                                       SymbolLockRegistry symbolLockRegistry,
                                       UserApiKeyService userApiKeyService,
                                       UserRepository userRepository,
                                       BinanceFuturesService binanceFuturesService) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.tradeRecordService = tradeRecordService;
        this.discordWebhookService = discordWebhookService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.userApiKeyService = userApiKeyService;
        this.userRepository = userRepository;
        this.binanceFuturesService = binanceFuturesService;

        this.wsClient = httpClient.newBuilder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * 取得或建立重連 executor（stopAllStreams 後重新啟動時會重建）
     */
    private ScheduledExecutorService getOrCreateReconnectExecutor() {
        ScheduledExecutorService existing = reconnectExecutorRef.get();
        if (existing != null && !existing.isShutdown()) {
            return existing;
        }
        ScheduledExecutorService newExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "multi-ws-reconnect");
            t.setDaemon(true);
            return t;
        });
        if (reconnectExecutorRef.compareAndSet(existing, newExecutor)) {
            return newExecutor;
        } else {
            // 另一個 thread 搶先建立了，關掉自己的
            newExecutor.shutdownNow();
            return reconnectExecutorRef.get();
        }
    }

    // ==================== 生命週期 ====================

    /**
     * 啟動所有符合條件的用戶 stream
     * 條件：enabled=true && autoTradeEnabled=true && 有 API Key
     *
     * 效能優化：一次 batch 查詢所有 API Key，避免 per-user 查詢 (N+1) 和
     * hasApiKey + getUserBinanceKeys 的 dual lookup (2N → 1)
     */
    public void startAllStreams() {
        shuttingDown = false;

        // Batch 查詢：一次取得所有 Binance API Key（解密後），避免 N+1 和 dual lookup
        Map<String, BinanceKeys> allKeys = userApiKeyService.getAllBinanceKeys("BINANCE");

        List<User> eligibleUsers = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(User::isAutoTradeEnabled)
                .filter(u -> allKeys.containsKey(u.getUserId()))
                .toList();

        log.info("多用戶 Data Stream 啟動: 找到 {} 個符合條件的用戶", eligibleUsers.size());

        for (User user : eligibleUsers) {
            try {
                // 使用已預載的 keys，不再重複查 DB
                BinanceKeys keys = allKeys.get(user.getUserId());
                startUserStreamWithKeys(user.getUserId(), formatUserDisplay(user), keys);
            } catch (Exception e) {
                log.error("用戶 {} Stream 啟動失敗: {}", user.getUserId(), e.getMessage());
            }
        }

        log.info("多用戶 Data Stream 啟動完成: {}/{} 成功",
                activeStreams.size(), eligibleUsers.size());
    }

    /**
     * 啟動單一用戶的 stream（外部呼叫入口，會查 DB 取得 keys）
     */
    public void startUserStream(String userId) {
        if (activeStreams.containsKey(userId)) {
            log.debug("用戶 {} 已有 active stream，跳過", userId);
            return;
        }

        Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
        if (keysOpt.isEmpty()) {
            log.warn("用戶 {} 未設定 API Key，無法啟動 stream", userId);
            return;
        }

        // 查詢用戶顯示名稱
        String displayName = userRepository.findById(userId)
                .map(this::formatUserDisplay).orElse(userId);
        startUserStreamWithKeys(userId, displayName, keysOpt.get());
    }

    /**
     * 啟動單一用戶的 stream（使用已預載的 keys，避免重複查 DB）
     * 供 startAllStreams() batch 模式使用
     */
    private void startUserStreamWithKeys(String userId, String displayName, BinanceKeys keys) {
        if (activeStreams.containsKey(userId)) {
            log.debug("用戶 {} 已有 active stream，跳過", userId);
            return;
        }

        UserStreamContext context = new UserStreamContext(userId, displayName, keys.apiKey(), keys.secretKey());

        try {
            String listenKey = createListenKey(keys.apiKey());
            context.setListenKey(listenKey);

            String wsUrl = binanceConfig.getWsBaseUrl() + listenKey;
            Request request = new Request.Builder().url(wsUrl).build();
            WebSocket ws = wsClient.newWebSocket(request, new PerUserWebSocketListener(context));
            context.setWebSocket(ws);

            activeStreams.put(userId, context);
            log.info("用戶 {} Stream 啟動成功, listenKey={}...",
                    userId, listenKey.substring(0, Math.min(listenKey.length(), 20)));
        } catch (Exception e) {
            log.error("用戶 {} Stream 建立失敗: {}", userId, e.getMessage());
            scheduleReconnect(userId, context);
            // 即使啟動失敗也放入 map，讓 reconnect 可以找到 context
            activeStreams.put(userId, context);
        }
    }

    /**
     * 停止單一用戶的 stream
     */
    public void stopUserStream(String userId) {
        UserStreamContext context = activeStreams.remove(userId);
        if (context == null) return;

        context.cancelPendingReconnect();
        context.setSelfInitiatedClose(true);

        WebSocket ws = context.getWebSocket();
        if (ws != null) {
            try {
                ws.close(1000, "user-stream-stop");
            } catch (Exception e) {
                log.debug("關閉用戶 {} WebSocket 時出錯: {}", userId, e.getMessage());
            }
        }

        deleteListenKey(context.getApiKey(), context.getListenKey());
        log.info("用戶 {} Stream 已停止", userId);
    }

    /**
     * 停止所有用戶 stream
     */
    public void stopAllStreams() {
        shuttingDown = true;
        log.info("正在停止所有用戶 Data Stream ({} 個)...", activeStreams.size());

        // 複製 key 避免 ConcurrentModification
        List<String> userIds = new ArrayList<>(activeStreams.keySet());
        for (String userId : userIds) {
            stopUserStream(userId);
        }

        ScheduledExecutorService executor = reconnectExecutorRef.getAndSet(null);
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("所有用戶 Data Stream 已停止");
    }

    // ==================== listenKey REST 工具 ====================

    String createListenKey(String apiKey) {
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("建立 listenKey 失敗: " + response.code() + " " + body);
            }
            JsonObject json = gson.fromJson(body, JsonObject.class);
            return json.get("listenKey").getAsString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("建立 listenKey 失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 對所有活躍用戶 PUT keepalive
     * 任一用戶 400/401 時觸發該用戶的 reconnect
     */
    public void keepAliveAll() {
        for (Map.Entry<String, UserStreamContext> entry : activeStreams.entrySet()) {
            String userId = entry.getKey();
            UserStreamContext context = entry.getValue();
            if (context.getListenKey() == null) continue;

            try {
                int code = keepAliveListenKey(context.getApiKey(), context.getListenKey());
                if (code == 400 || code == 401) {
                    log.warn("用戶 {} listenKey keepalive 失敗 ({}), 觸發重連", userId, code);
                    // 先標記自發關閉，避免舊 listener 的 onClosed 觸發額外 reconnect
                    context.setSelfInitiatedClose(true);
                    scheduleReconnect(userId, context);
                }
            } catch (Exception e) {
                log.error("用戶 {} keepalive 異常: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * PUT keepalive，回傳 HTTP status code
     */
    int keepAliveListenKey(String apiKey, String listenKey) {
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        } catch (Exception e) {
            log.error("keepAlive request 失敗: {}", e.getMessage());
            return -1;
        }
    }

    private void deleteListenKey(String apiKey, String listenKey) {
        if (listenKey == null) return;
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            log.debug("用戶 listenKey 已刪除: {}", response.isSuccessful());
        } catch (Exception e) {
            log.warn("刪除 listenKey 失敗: {}", e.getMessage());
        }
    }

    // ==================== 重連機制（per-user）====================

    /**
     * 排程重連某一個用戶的 stream
     */
    void scheduleReconnect(String userId, UserStreamContext context) {
        if (shuttingDown) return;

        int attempt = context.incrementReconnectAttempts();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("用戶 {} 重連次數已達上限 ({})，停止重試", userId, MAX_RECONNECT_ATTEMPTS);
            String msg = String.format("已嘗試 %d 次重連，全部失敗\n請通知管理員檢查！", MAX_RECONNECT_ATTEMPTS);
            discordWebhookService.sendNotificationToUser(userId,
                    "🚨 User Data Stream 重連失敗", msg,
                    DiscordWebhookService.COLOR_RED);
            discordWebhookService.sendNotificationToAdmins(
                    context.getDisplayName(),
                    "🚨 User Data Stream 重連失敗", msg,
                    DiscordWebhookService.COLOR_RED);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
        log.info("用戶 {} 重連排程: 第 {} 次嘗試，延遲 {}ms", userId, attempt, delay);

        context.cancelPendingReconnect();

        try {
            ScheduledFuture<?> future = getOrCreateReconnectExecutor().schedule(() -> {
                if (!shuttingDown) {
                    reconnect(userId);
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            context.setPendingReconnect(future);
        } catch (RejectedExecutionException e) {
            log.debug("用戶 {} 重連排程被拒絕（executor 已關閉）", userId);
        }
    }

    /**
     * 執行重連：關閉舊 socket → 刪除 listenKey → 重建
     */
    void reconnect(String userId) {
        UserStreamContext context = activeStreams.get(userId);
        if (context == null) {
            log.debug("用戶 {} 已不在 activeStreams，跳過 reconnect", userId);
            return;
        }

        synchronized (context) {
            try {
                // 關閉舊 WebSocket
                WebSocket oldWs = context.getWebSocket();
                if (oldWs != null) {
                    context.setSelfInitiatedClose(true);
                    try {
                        oldWs.close(1000, "reconnecting");
                    } catch (Exception e) {
                        log.debug("關閉用戶 {} 舊 WebSocket 時出錯: {}", userId, e.getMessage());
                    }
                }

                // 刪除舊 listenKey
                deleteListenKey(context.getApiKey(), context.getListenKey());

                // 重新取得 API Key（可能已更新）
                Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
                if (keysOpt.isEmpty()) {
                    log.warn("用戶 {} API Key 已不存在，移除 stream", userId);
                    activeStreams.remove(userId);
                    return;
                }

                // 使用最新的 API Key（用戶可能已更換）
                BinanceKeys freshKeys = keysOpt.get();
                context.updateApiKey(freshKeys.apiKey(), freshKeys.secretKey());

                // 重建 stream
                String listenKey = createListenKey(freshKeys.apiKey());
                context.setListenKey(listenKey);

                String wsUrl = binanceConfig.getWsBaseUrl() + listenKey;
                Request request = new Request.Builder().url(wsUrl).build();
                WebSocket ws = wsClient.newWebSocket(request, new PerUserWebSocketListener(context));
                context.setWebSocket(ws);
                context.setSelfInitiatedClose(false);
                context.resetReconnectAttempts();

                log.info("用戶 {} 重連成功", userId);
            } catch (Exception e) {
                context.setSelfInitiatedClose(false);
                log.error("用戶 {} 重連失敗: {}", userId, e.getMessage());
                scheduleReconnect(userId, context);
            }
        }
    }

    // ==================== WebSocket Listener（per-user）====================

    /**
     * 每個用戶獨立的 WebSocket Listener
     * 在處理事件前設定 ThreadLocal userId，處理完清除
     */
    private class PerUserWebSocketListener extends WebSocketListener {

        private final UserStreamContext context;
        private final OrderEventHandler orderEventHandler;

        PerUserWebSocketListener(UserStreamContext context) {
            this.context = context;
            // per-user 版：通知走 sendNotificationToUser + Admin
            this.orderEventHandler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry,
                    (title, msg, color) -> discordWebhookService.sendNotificationToUser(
                            context.getUserId(), title, msg, color),
                    (title, msg, color) -> discordWebhookService.sendNotificationToAdmins(
                            context.getDisplayName(), title, msg, color),
                    binanceFuturesService::cancelSLTPOrders,
                    gson, "用戶 " + context.getUserId() + " ");
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            context.resetOnConnected();
            log.info("用戶 {} WebSocket 已連線", context.getUserId());

            if (context.isAlertSent()) {
                context.setAlertSent(false);
                String msg = "WebSocket 連線已重新建立\n止損/止盈觸發將正常同步至 DB";
                discordWebhookService.sendNotificationToUser(context.getUserId(),
                        "✅ User Data Stream 已恢復", msg,
                        DiscordWebhookService.COLOR_GREEN);
                discordWebhookService.sendNotificationToAdmins(
                        context.getDisplayName(),
                        "✅ User Data Stream 已恢復", msg,
                        DiscordWebhookService.COLOR_GREEN);
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            context.updateLastMessageTime();

            // 使用 TradeContext bridge 設入 userId，讓 TradeRecordService 走 per-user 查詢
            TradeContext ctx = TradeContext.forWebSocket(context.getUserId());
            ctx.installThreadLocals();
            try {
                JsonObject json = gson.fromJson(text, JsonObject.class);
                String eventType = json.has("e") ? json.get("e").getAsString() : "";

                switch (eventType) {
                    case "ORDER_TRADE_UPDATE":
                        orderEventHandler.handleOrderTradeUpdate(json);
                        break;
                    case "ALGO_UPDATE":
                        orderEventHandler.handleAlgoUpdate(json);
                        break;
                    case "ACCOUNT_UPDATE":
                        orderEventHandler.handleAccountUpdate(json);
                        break;
                    case "listenKeyExpired":
                        log.warn("用戶 {} ListenKey 已過期，排程重連...", context.getUserId());
                        context.resetReconnectAttempts();  // 過期是正常生命週期，不累積計數
                        scheduleReconnect(context.getUserId(), context);
                        break;
                    default:
                        log.debug("用戶 {} unknown event: {}", context.getUserId(), eventType);
                }

            } catch (Exception e) {
                log.error("用戶 {} 處理 WebSocket 訊息失敗: {}", context.getUserId(), e.getMessage(), e);
            } finally {
                TradeContext.clearThreadLocals();
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            log.info("用戶 {} WebSocket closing: code={} reason={}", context.getUserId(), code, reason);
            context.setConnected(false);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("用戶 {} WebSocket closed: code={} reason={}", context.getUserId(), code, reason);
            context.setConnected(false);

            if (context.isSelfInitiatedClose() || shuttingDown) {
                log.debug("用戶 {} 自發關閉，跳過 scheduleReconnect", context.getUserId());
                return;
            }
            scheduleReconnect(context.getUserId(), context);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("用戶 {} WebSocket failure: {}", context.getUserId(), t.getMessage());
            context.setConnected(false);

            if (!shuttingDown) {
                if (!context.isAlertSent()) {
                    context.setAlertSent(true);
                    String msg = "WebSocket 連線中斷: " + t.getMessage()
                            + "\n止損/止盈觸發暫時無法同步至 DB"
                            + "\n正在嘗試自動重連...";
                    discordWebhookService.sendNotificationToUser(context.getUserId(),
                            "🚨 User Data Stream 斷線", msg,
                            DiscordWebhookService.COLOR_RED);
                    discordWebhookService.sendNotificationToAdmins(
                            context.getDisplayName(),
                            "🚨 User Data Stream 斷線", msg,
                            DiscordWebhookService.COLOR_RED);
                }
                scheduleReconnect(context.getUserId(), context);
            }
        }

    }

    // ==================== 狀態查詢 ====================

    public Map<String, Object> getAllStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "multi-user");
        result.put("totalStreams", activeStreams.size());

        // 前端期望頂層 "connected" — 任一用戶 stream 連上即為 true
        boolean anyConnected = activeStreams.values().stream()
                .anyMatch(UserStreamContext::isConnected);
        result.put("connected", anyConnected);

        result.put("shuttingDown", shuttingDown);

        Map<String, Object> streams = new LinkedHashMap<>();
        for (Map.Entry<String, UserStreamContext> entry : activeStreams.entrySet()) {
            streams.put(entry.getKey(), entry.getValue().getStatus());
        }
        result.put("streams", streams);
        return result;
    }

    public Map<String, Object> getUserStatus(String userId) {
        UserStreamContext context = activeStreams.get(userId);
        return context != null ? context.getStatus() : Map.of("error", "stream not found");
    }

    // ==================== 測試用 accessor（package-private）====================

    ConcurrentHashMap<String, UserStreamContext> getActiveStreams() {
        return activeStreams;
    }

    ScheduledExecutorService getReconnectExecutor() {
        return reconnectExecutorRef.get();
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 格式化用戶顯示名稱：name (email)，name 為空時 fallback 到 email
     */
    private String formatUserDisplay(User user) {
        String name = user.getName();
        String email = user.getEmail();
        if (name != null && !name.isBlank()) {
            return name + " (" + email + ")";
        }
        return email != null ? email : user.getUserId();
    }
}
