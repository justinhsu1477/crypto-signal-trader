package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

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
    private final DiscordWebhookService discordWebhookService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final UserApiKeyService userApiKeyService;
    private final UserRepository userRepository;
    private final Gson gson = new Gson();

    // 所有用戶的 stream 狀態
    private final ConcurrentHashMap<String, UserStreamContext> activeStreams = new ConcurrentHashMap<>();

    // 共用重連排程器（所有用戶共用，避免 per-user thread 浪費）
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "multi-ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean shuttingDown = false;

    // 重連配置（與單用戶服務一致）
    static final long BASE_RECONNECT_DELAY_MS = 1000;
    static final long MAX_RECONNECT_DELAY_MS = 60_000;
    static final int MAX_RECONNECT_ATTEMPTS = 20;

    public MultiUserDataStreamManager(OkHttpClient httpClient,
                                       BinanceConfig binanceConfig,
                                       TradeRecordService tradeRecordService,
                                       DiscordWebhookService discordWebhookService,
                                       SymbolLockRegistry symbolLockRegistry,
                                       UserApiKeyService userApiKeyService,
                                       UserRepository userRepository) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.tradeRecordService = tradeRecordService;
        this.discordWebhookService = discordWebhookService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.userApiKeyService = userApiKeyService;
        this.userRepository = userRepository;

        this.wsClient = httpClient.newBuilder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    // ==================== 生命週期 ====================

    /**
     * 啟動所有符合條件的用戶 stream
     * 條件：enabled=true && autoTradeEnabled=true && 有 API Key
     */
    public void startAllStreams() {
        shuttingDown = false;
        List<User> eligibleUsers = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(User::isAutoTradeEnabled)
                .filter(u -> userApiKeyService.hasApiKey(u.getUserId()))
                .toList();

        log.info("多用戶 Data Stream 啟動: 找到 {} 個符合條件的用戶", eligibleUsers.size());

        for (User user : eligibleUsers) {
            try {
                startUserStream(user.getUserId());
            } catch (Exception e) {
                log.error("用戶 {} Stream 啟動失敗: {}", user.getUserId(), e.getMessage());
            }
        }

        log.info("多用戶 Data Stream 啟動完成: {}/{} 成功",
                activeStreams.size(), eligibleUsers.size());
    }

    /**
     * 啟動單一用戶的 stream
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

        BinanceKeys keys = keysOpt.get();
        UserStreamContext context = new UserStreamContext(userId, keys.apiKey(), keys.secretKey());

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

        reconnectExecutor.shutdownNow();
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
            discordWebhookService.sendNotificationToUser(userId,
                    "🚨 User Data Stream 重連失敗",
                    String.format("已嘗試 %d 次重連，全部失敗\n請通知管理員檢查！", MAX_RECONNECT_ATTEMPTS),
                    DiscordWebhookService.COLOR_RED);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
        log.info("用戶 {} 重連排程: 第 {} 次嘗試，延遲 {}ms", userId, attempt, delay);

        context.cancelPendingReconnect();

        try {
            ScheduledFuture<?> future = reconnectExecutor.schedule(() -> {
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

                // 重建 stream
                String listenKey = createListenKey(context.getApiKey());
                context.setListenKey(listenKey);

                String wsUrl = binanceConfig.getWsBaseUrl() + listenKey;
                Request request = new Request.Builder().url(wsUrl).build();
                WebSocket ws = wsClient.newWebSocket(request, new PerUserWebSocketListener(context));
                context.setWebSocket(ws);
                context.setSelfInitiatedClose(false);

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

        PerUserWebSocketListener(UserStreamContext context) {
            this.context = context;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            context.resetOnConnected();
            log.info("用戶 {} WebSocket 已連線", context.getUserId());

            if (context.isAlertSent()) {
                context.setAlertSent(false);
                discordWebhookService.sendNotificationToUser(context.getUserId(),
                        "✅ User Data Stream 已恢復",
                        "WebSocket 連線已重新建立\n止損/止盈觸發將正常同步至 DB",
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
                String eventType = json.has("e") ? json.get("e").getAsString() : "";

                switch (eventType) {
                    case "ORDER_TRADE_UPDATE":
                        handleOrderTradeUpdate(json);
                        break;
                    case "ACCOUNT_UPDATE":
                        log.debug("用戶 {} ACCOUNT_UPDATE received (ignored)", context.getUserId());
                        break;
                    case "listenKeyExpired":
                        log.warn("用戶 {} ListenKey 已過期，觸發重連...", context.getUserId());
                        reconnect(context.getUserId());
                        break;
                    default:
                        log.debug("用戶 {} unknown event: {}", context.getUserId(), eventType);
                }
            } catch (Exception e) {
                log.error("用戶 {} 處理 WebSocket 訊息失敗: {}", context.getUserId(), e.getMessage(), e);
            } finally {
                TradeRecordService.clearCurrentUserId();
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
                    discordWebhookService.sendNotificationToUser(context.getUserId(),
                            "🚨 User Data Stream 斷線",
                            "WebSocket 連線中斷: " + t.getMessage()
                                    + "\n止損/止盈觸發暫時無法同步至 DB"
                                    + "\n正在嘗試自動重連...",
                            DiscordWebhookService.COLOR_RED);
                }
                scheduleReconnect(context.getUserId(), context);
            }
        }

        // ==================== 事件處理（複用原 Service 邏輯）====================

        /**
         * 處理 ORDER_TRADE_UPDATE 事件
         * 邏輯與 BinanceUserDataStreamService.handleOrderTradeUpdate 完全一致
         * ThreadLocal userId 已在 onMessage 中設定
         */
        private void handleOrderTradeUpdate(JsonObject event) {
            JsonObject order = event.getAsJsonObject("o");
            if (order == null) {
                log.warn("用戶 {} ORDER_TRADE_UPDATE missing 'o' field", context.getUserId());
                return;
            }

            String symbol = order.get("s").getAsString();
            String orderType = order.get("o").getAsString();
            String orderStatus = order.get("X").getAsString();
            String orderId = String.valueOf(order.get("i").getAsLong());
            String side = order.get("S").getAsString();

            log.info("用戶 {} ORDER_TRADE_UPDATE: {} {} {} status={} orderId={}",
                    context.getUserId(), symbol, side, orderType, orderStatus, orderId);

            // SL/TP 被取消或過期 → 告警保護消失
            if (("CANCELED".equals(orderStatus) || "EXPIRED".equals(orderStatus))
                    && ("STOP_MARKET".equals(orderType) || "TAKE_PROFIT_MARKET".equals(orderType))) {
                handleProtectionLost(symbol, orderType, orderId, orderStatus);
                return;
            }

            // SL/TP 部分成交
            if ("PARTIALLY_FILLED".equals(orderStatus)
                    && ("STOP_MARKET".equals(orderType) || "TAKE_PROFIT_MARKET".equals(orderType))) {
                handlePartialFill(order, symbol, orderType, orderId);
                return;
            }

            // 非 FILLED → 忽略
            if (!"FILLED".equals(orderStatus)) {
                log.debug("用戶 {} 訂單未完全成交 ({}), 忽略", context.getUserId(), orderStatus);
                return;
            }

            double avgPrice = order.get("ap").getAsDouble();
            double filledQty = order.get("z").getAsDouble();
            double commission = order.get("n").getAsDouble();
            String commissionAsset = order.get("N").getAsString();
            double realizedProfit = order.get("rp").getAsDouble();
            long transactionTime = order.get("T").getAsLong();

            if (!"USDT".equals(commissionAsset)) {
                commission = avgPrice * filledQty * 0.0004;
            }

            switch (orderType) {
                case "STOP_MARKET":
                    log.info("用戶 {} 止損觸發: {} @ {}", context.getUserId(), symbol, avgPrice);
                    processStreamClose(symbol, avgPrice, filledQty, commission,
                            realizedProfit, orderId, "SL_TRIGGERED", transactionTime);
                    break;
                case "TAKE_PROFIT_MARKET":
                    log.info("用戶 {} 止盈觸發: {} @ {}", context.getUserId(), symbol, avgPrice);
                    processStreamClose(symbol, avgPrice, filledQty, commission,
                            realizedProfit, orderId, "TP_TRIGGERED", transactionTime);
                    break;
                case "LIMIT":
                case "MARKET":
                    log.info("用戶 {} {} 訂單成交: {} {} @ {}",
                            context.getUserId(), orderType, symbol, side, avgPrice);
                    break;
                default:
                    log.debug("用戶 {} 非關注訂單類型: {} {}", context.getUserId(), orderType, symbol);
            }
        }

        private void handlePartialFill(JsonObject order, String symbol, String orderType, String orderId) {
            double filledQty = order.get("z").getAsDouble();
            double origQty = order.get("q").getAsDouble();
            double remainingQty = origQty - filledQty;
            boolean isSL = "STOP_MARKET".equals(orderType);

            log.warn("用戶 {} SL/TP 部分成交: {} {} filled={}/{}",
                    context.getUserId(), symbol, orderType, filledQty, origQty);

            try {
                tradeRecordService.recordOrderEvent(symbol,
                        isSL ? "SL_PARTIAL_FILL" : "TP_PARTIAL_FILL",
                        null, gson.toJson(java.util.Map.of(
                                "orderId", orderId, "filledQty", filledQty,
                                "origQty", origQty, "remainingQty", remainingQty)));
            } catch (Exception e) {
                log.error("用戶 {} 記錄部分成交事件失敗: {}", context.getUserId(), e.getMessage());
            }

            discordWebhookService.sendNotificationToUser(context.getUserId(),
                    "⚠️ " + (isSL ? "止損" : "止盈") + "單部分成交",
                    String.format("%s %s\n成交: %.4f / %.4f\n剩餘 %.4f 等待完全成交",
                            symbol, orderType, filledQty, origQty, remainingQty),
                    DiscordWebhookService.COLOR_YELLOW);
        }

        private void handleProtectionLost(String symbol, String orderType, String orderId, String reason) {
            boolean isSL = "STOP_MARKET".equals(orderType);
            String label = isSL ? "止損" : "止盈";

            log.warn("用戶 {} {} 被{}: {} orderId={}",
                    context.getUserId(), label, reason, symbol, orderId);

            try {
                tradeRecordService.recordProtectionLost(symbol, orderType, orderId, reason);
            } catch (Exception e) {
                log.error("用戶 {} 記錄保護消失事件失敗: {}", context.getUserId(), e.getMessage());
            }

            int color = isSL ? DiscordWebhookService.COLOR_RED : DiscordWebhookService.COLOR_YELLOW;
            String urgency = isSL ? "🚨" : "⚠️";

            discordWebhookService.sendNotificationToUser(context.getUserId(),
                    urgency + " " + label + "單被取消",
                    String.format("%s\n訂單號: %s\n原因: %s\n%s",
                            symbol, orderId, reason,
                            isSL ? "⚠️ 持倉已失去止損保護！請立即檢查" : "止盈保護已消失，止損仍有效"),
                    color);
        }

        private void processStreamClose(String symbol, double exitPrice, double exitQty,
                                          double commission, double realizedProfit,
                                          String orderId, String exitReason, long transactionTime) {
            ReentrantLock lock = symbolLockRegistry.getLock(symbol);
            lock.lock();
            try {
                tradeRecordService.recordCloseFromStream(
                        symbol, exitPrice, exitQty, commission,
                        realizedProfit, orderId, exitReason, transactionTime);

                String emoji = "SL_TRIGGERED".equals(exitReason) ? "🛑" : "🎯";
                String label = "SL_TRIGGERED".equals(exitReason) ? "止損觸發" : "止盈觸發";
                discordWebhookService.sendNotificationToUser(context.getUserId(),
                        emoji + " " + label + " (自動)",
                        String.format("%s\n出場價: %.2f\n數量: %.4f\n手續費: %.4f USDT\n已實現損益: %.2f USDT",
                                symbol, exitPrice, exitQty, commission, realizedProfit),
                        "SL_TRIGGERED".equals(exitReason)
                                ? DiscordWebhookService.COLOR_RED
                                : DiscordWebhookService.COLOR_GREEN);
            } catch (Exception e) {
                log.error("用戶 {} WebSocket 平倉記錄失敗: {} {} - {}",
                        context.getUserId(), symbol, exitReason, e.getMessage(), e);
                discordWebhookService.sendNotificationToUser(context.getUserId(),
                        "⚠️ WebSocket 平倉記錄失敗",
                        String.format("%s %s\norderId=%s\n錯誤: %s\n請手動檢查 DB",
                                symbol, exitReason, orderId, e.getMessage()),
                        DiscordWebhookService.COLOR_YELLOW);
            } finally {
                lock.unlock();
            }
        }
    }

    // ==================== 狀態查詢 ====================

    public Map<String, Object> getAllStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "multi-user");
        result.put("totalStreams", activeStreams.size());
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
        return reconnectExecutor;
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }
}
