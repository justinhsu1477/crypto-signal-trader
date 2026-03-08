package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 多用戶 User Data Stream 管理器（多交易所版）
 *
 * 當 multi-user.enabled=true 時，為每個啟用的用戶各開一條
 * 獨立的 WebSocket 連線，監聽 SL/TP 觸發、保護消失等事件。
 *
 * 設計：
 * - ConcurrentHashMap<userId, UserStreamContext> 管理所有用戶 stream
 * - 共用一個 ScheduledExecutorService 處理所有用戶的重連排程
 * - ExchangeStreamProvider 抽象不同交易所的連線/保活/斷線邏輯
 * - PerUserWebSocketListener 根據交易所分派事件處理
 * - WebSocket Listener 在處理事件前設定 ThreadLocal userId，
 *   讓 TradeRecordService.resolveOpenTrade 自動走 per-user 查詢
 * - Discord 通知走 sendNotificationToUser(userId, ...) per-user webhook
 */
@Slf4j
@Component
public class MultiUserDataStreamManager {

    private final OkHttpClient wsClient;
    private final TradeRecordService tradeRecordService;
    private final NotificationService discordWebhookService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final UserApiKeyService userApiKeyService;
    private final UserRepository userRepository;
    private final Gson gson = new Gson();

    // 交易所 StreamProvider 映射（BINANCE → provider, BYBIT → provider）
    private final Map<String, ExchangeStreamProvider> streamProviders;

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

    public MultiUserDataStreamManager(OkHttpClient httpClient,
                                       TradeRecordService tradeRecordService,
                                       NotificationService discordWebhookService,
                                       SymbolLockRegistry symbolLockRegistry,
                                       UserApiKeyService userApiKeyService,
                                       UserRepository userRepository,
                                       List<ExchangeStreamProvider> providers) {
        this.tradeRecordService = tradeRecordService;
        this.discordWebhookService = discordWebhookService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.userApiKeyService = userApiKeyService;
        this.userRepository = userRepository;

        this.wsClient = httpClient.newBuilder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // 建立 exchange → provider 映射
        Map<String, ExchangeStreamProvider> providerMap = new HashMap<>();
        for (ExchangeStreamProvider p : providers) {
            providerMap.put(p.getExchangeName(), p);
        }
        this.streamProviders = Collections.unmodifiableMap(providerMap);
        log.info("已註冊 {} 個交易所 StreamProvider: {}", streamProviders.size(), streamProviders.keySet());
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
     * 啟動所有符合條件的用戶 stream（多交易所版）
     * 條件：enabled=true && autoTradeEnabled=true && 有 API Key
     *
     * 效能優化：
     * - 一次查詢所有用戶的交易所映射（getUserIdExchangeMap）
     * - 按交易所分組 batch 查詢 API Key
     * - 避免 per-user N+1 查詢
     */
    public void startAllStreams() {
        shuttingDown = false;

        // Batch 查詢：取得所有用戶的交易所映射 + API Key
        Map<String, String> userExchangeMap = userApiKeyService.getUserIdExchangeMap();

        // 按交易所分組 batch 查詢 API Key（避免 N+1）
        Map<String, Map<String, ExchangeKeys>> keysByExchange = new HashMap<>();
        for (String exchange : streamProviders.keySet()) {
            Map<String, ExchangeKeys> keys = userApiKeyService.getAllExchangeKeys(exchange);
            if (!keys.isEmpty()) {
                keysByExchange.put(exchange, keys);
            }
        }

        // 合併：每個用戶取對應交易所的 keys
        Map<String, ExchangeKeys> allUserKeys = new HashMap<>();
        for (Map.Entry<String, String> entry : userExchangeMap.entrySet()) {
            String userId = entry.getKey();
            String exchange = entry.getValue();
            Map<String, ExchangeKeys> exchangeKeys = keysByExchange.get(exchange);
            if (exchangeKeys != null && exchangeKeys.containsKey(userId)) {
                allUserKeys.put(userId, exchangeKeys.get(userId));
            }
        }

        List<User> eligibleUsers = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(User::isAutoTradeEnabled)
                .filter(u -> allUserKeys.containsKey(u.getUserId()))
                .toList();

        log.info("多用戶 Data Stream 啟動: 找到 {} 個符合條件的用戶", eligibleUsers.size());

        for (User user : eligibleUsers) {
            try {
                String exchange = userExchangeMap.getOrDefault(user.getUserId(), "BINANCE");
                ExchangeKeys keys = allUserKeys.get(user.getUserId());
                startUserStreamWithKeys(user.getUserId(), formatUserDisplay(user), exchange, keys);
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

        // 取得用戶的交易所 + API Key
        Optional<Map.Entry<String, ExchangeKeys>> keysOpt = userApiKeyService.getUserPrimaryExchangeKeys(userId);
        if (keysOpt.isEmpty()) {
            log.warn("用戶 {} 未設定 API Key，無法啟動 stream", userId);
            return;
        }

        String exchange = keysOpt.get().getKey();
        ExchangeKeys keys = keysOpt.get().getValue();

        // 查詢用戶顯示名稱
        String displayName = userRepository.findById(userId)
                .map(this::formatUserDisplay).orElse(userId);
        startUserStreamWithKeys(userId, displayName, exchange, keys);
    }

    /**
     * 啟動單一用戶的 stream（使用已預載的 keys，避免重複查 DB）
     * 供 startAllStreams() batch 模式使用
     */
    private void startUserStreamWithKeys(String userId, String displayName, String exchange, ExchangeKeys keys) {
        if (activeStreams.containsKey(userId)) {
            log.debug("用戶 {} 已有 active stream，跳過", userId);
            return;
        }

        ExchangeStreamProvider provider = streamProviders.get(exchange);
        if (provider == null) {
            log.error("用戶 {} 交易所 {} 無對應 StreamProvider，跳過", userId, exchange);
            return;
        }

        UserStreamContext context = new UserStreamContext(userId, displayName, exchange,
                keys.apiKey(), keys.secretKey());

        try {
            ExchangeStreamProvider.ConnectResult result = provider.connect(
                    keys.apiKey(), keys.secretKey(), wsClient,
                    new PerUserWebSocketListener(context));
            context.setWebSocket(result.webSocket());
            context.setListenKey(result.connectionContext());

            activeStreams.put(userId, context);
            log.info("用戶 {} [{}] Stream 啟動成功", userId, exchange);
        } catch (Exception e) {
            log.error("用戶 {} [{}] Stream 建立失敗: {}", userId, exchange, e.getMessage());
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

        // 透過 provider 清理連線（Binance: delete listenKey; Bybit: no-op）
        ExchangeStreamProvider provider = streamProviders.get(context.getExchange());
        if (provider != null) {
            provider.cleanup(context.getApiKey(), context.getListenKey());
        }
        log.info("用戶 {} [{}] Stream 已停止", userId, context.getExchange());
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

    // ==================== keepAlive（多交易所版）====================

    /**
     * 對所有活躍用戶做 keepAlive
     * Binance: PUT listenKey keepalive；Bybit: no-op
     * 任一用戶 400/401 時觸發該用戶的 reconnect
     */
    public void keepAliveAll() {
        for (Map.Entry<String, UserStreamContext> entry : activeStreams.entrySet()) {
            String userId = entry.getKey();
            UserStreamContext context = entry.getValue();

            ExchangeStreamProvider provider = streamProviders.get(context.getExchange());
            if (provider == null) continue;

            try {
                int code = provider.keepAlive(context.getApiKey(), context.getListenKey());
                if (code == 400 || code == 401) {
                    log.warn("用戶 {} [{}] keepalive 失敗 ({}), 觸發重連",
                            userId, context.getExchange(), code);
                    context.setSelfInitiatedClose(true);
                    scheduleReconnect(userId, context);
                }
            } catch (Exception e) {
                log.error("用戶 {} keepalive 異常: {}", userId, e.getMessage());
            }
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
     * 執行重連：關閉舊 socket → 清理 → 取最新 API Key → 重建
     */
    void reconnect(String userId) {
        UserStreamContext context = activeStreams.get(userId);
        if (context == null) {
            log.debug("用戶 {} 已不在 activeStreams，跳過 reconnect", userId);
            return;
        }

        synchronized (context) {
            try {
                String exchange = context.getExchange();
                ExchangeStreamProvider provider = streamProviders.get(exchange);
                if (provider == null) {
                    log.error("用戶 {} 交易所 {} 無 StreamProvider，移除 stream", userId, exchange);
                    activeStreams.remove(userId);
                    return;
                }

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

                // 清理舊連線（Binance: delete listenKey; Bybit: no-op）
                provider.cleanup(context.getApiKey(), context.getListenKey());

                // 重新取得 API Key（可能已更新）
                Optional<Map.Entry<String, ExchangeKeys>> keysOpt = userApiKeyService.getUserPrimaryExchangeKeys(userId);
                if (keysOpt.isEmpty()) {
                    log.warn("用戶 {} API Key 已不存在，移除 stream", userId);
                    activeStreams.remove(userId);
                    return;
                }

                // 使用最新的 API Key（用戶可能已更換）
                ExchangeKeys freshKeys = keysOpt.get().getValue();
                context.updateApiKey(freshKeys.apiKey(), freshKeys.secretKey());

                // 重建 stream
                ExchangeStreamProvider.ConnectResult result = provider.connect(
                        freshKeys.apiKey(), freshKeys.secretKey(), wsClient,
                        new PerUserWebSocketListener(context));
                context.setWebSocket(result.webSocket());
                context.setListenKey(result.connectionContext());
                context.setSelfInitiatedClose(false);
                context.resetReconnectAttempts();

                log.info("用戶 {} [{}] 重連成功", userId, exchange);
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
     *
     * 根據交易所分派事件：
     * - Binance: ORDER_TRADE_UPDATE / ALGO_UPDATE / ACCOUNT_UPDATE / listenKeyExpired
     * - Bybit: onOpen 發送 auth + subscribe → execution / position topics
     */
    private class PerUserWebSocketListener extends WebSocketListener {

        private final UserStreamContext context;
        private final OrderEventHandler orderEventHandler;
        private volatile boolean bybitAuthenticated = false;

        PerUserWebSocketListener(UserStreamContext context) {
            this.context = context;
            String exchange = context.getExchange();
            // per-user 版：通知走 sendNotificationToUser + Admin
            this.orderEventHandler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry,
                    (title, msg, color) -> discordWebhookService.sendNotificationToUser(
                            context.getUserId(), title, msg, color),
                    (title, msg, color) -> discordWebhookService.sendNotificationToAdmins(
                            context.getDisplayName(), title, msg, color),
                    gson, "用戶 " + context.getUserId() + " ", exchange);
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            context.resetOnConnected();
            log.info("用戶 {} [{}] WebSocket 已連線", context.getUserId(), context.getExchange());

            // Bybit: 連線後需要認證 + 訂閱
            if ("BYBIT".equals(context.getExchange())) {
                String authMsg = BybitStreamProvider.buildAuthMessage(
                        context.getApiKey(), context.getSecretKey());
                ws.send(authMsg);
                log.debug("用戶 {} Bybit auth 訊息已發送", context.getUserId());
            }

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

            // 設定 ThreadLocal userId，讓 TradeRecordService 走 per-user 查詢
            TradeRecordService.setCurrentUserId(context.getUserId());
            try {
                JsonObject json = gson.fromJson(text, JsonObject.class);

                if ("BYBIT".equals(context.getExchange())) {
                    handleBybitMessage(ws, json);
                } else {
                    handleBinanceMessage(json);
                }
            } catch (Exception e) {
                log.error("用戶 {} 處理 WebSocket 訊息失敗: {}", context.getUserId(), e.getMessage(), e);
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }

        /**
         * 處理 Binance 事件（原有邏輯）
         */
        private void handleBinanceMessage(JsonObject json) {
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
                    context.resetReconnectAttempts();
                    scheduleReconnect(context.getUserId(), context);
                    break;
                default:
                    log.debug("用戶 {} unknown Binance event: {}", context.getUserId(), eventType);
            }
        }

        /**
         * 處理 Bybit 事件
         *
         * Bybit V5 Private WebSocket 訊息格式：
         * - Auth 回應: {"op":"auth","success":true,...}
         * - Subscribe 回應: {"op":"subscribe","success":true,...}
         * - Execution 事件: {"topic":"execution","data":[{...}]}
         * - Position 事件: {"topic":"position","data":[{...}]}
         * - Pong: {"op":"pong",...}
         */
        private void handleBybitMessage(WebSocket ws, JsonObject json) {
            // 1. 控制訊息（auth / subscribe / pong）
            if (json.has("op")) {
                String op = json.get("op").getAsString();
                boolean success = json.has("success") && json.get("success").getAsBoolean();

                switch (op) {
                    case "auth":
                        if (success) {
                            bybitAuthenticated = true;
                            log.info("用戶 {} Bybit auth 成功，發送 subscribe", context.getUserId());
                            ws.send(BybitStreamProvider.buildSubscribeMessage());
                        } else {
                            String retMsg = json.has("ret_msg") ? json.get("ret_msg").getAsString() : "unknown";
                            log.error("用戶 {} Bybit auth 失敗: {}", context.getUserId(), retMsg);
                            scheduleReconnect(context.getUserId(), context);
                        }
                        break;
                    case "subscribe":
                        if (success) {
                            log.info("用戶 {} Bybit subscribe 成功", context.getUserId());
                        } else {
                            String retMsg = json.has("ret_msg") ? json.get("ret_msg").getAsString() : "unknown";
                            log.error("用戶 {} Bybit subscribe 失敗: {}", context.getUserId(), retMsg);
                        }
                        break;
                    case "pong":
                        log.debug("用戶 {} Bybit pong", context.getUserId());
                        break;
                    default:
                        log.debug("用戶 {} Bybit unknown op: {}", context.getUserId(), op);
                }
                return;
            }

            // 2. 數據推送（execution / position）
            if (json.has("topic") && json.has("data")) {
                String topic = json.get("topic").getAsString();
                JsonArray dataArray = json.getAsJsonArray("data");

                for (int i = 0; i < dataArray.size(); i++) {
                    JsonObject data = dataArray.get(i).getAsJsonObject();
                    switch (topic) {
                        case "execution":
                            orderEventHandler.handleBybitExecution(data);
                            break;
                        case "position":
                            orderEventHandler.handleBybitPosition(data);
                            break;
                        default:
                            log.debug("用戶 {} Bybit unknown topic: {}", context.getUserId(), topic);
                    }
                }
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
            bybitAuthenticated = false;

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
            bybitAuthenticated = false;

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
