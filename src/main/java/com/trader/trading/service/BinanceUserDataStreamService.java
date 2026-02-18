package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Binance Futures User Data Stream 服務
 *
 * 透過 WebSocket 監聽帳戶事件：
 * - STOP_MARKET FILLED → recordCloseFromStream("SL_TRIGGERED")
 * - TAKE_PROFIT_MARKET FILLED → recordCloseFromStream("TP_TRIGGERED")
 * - STOP_MARKET/TAKE_PROFIT_MARKET CANCELED/EXPIRED → 告警保護消失
 *
 * 生命週期：
 * - @PostConstruct → 建立 listenKey + 連線 WebSocket
 * - @Scheduled(30min) → PUT keepalive 延長 listenKey
 * - @PreDestroy → 關閉 WebSocket + 刪除 listenKey
 *
 * 重連機制：指數退避 1s → 2s → 4s → ... → 60s，最多 20 次
 */
@Slf4j
@Service
public class BinanceUserDataStreamService {

    private final OkHttpClient httpClient;
    private final OkHttpClient wsClient;
    private final BinanceConfig binanceConfig;
    private final TradeRecordService tradeRecordService;
    private final DiscordWebhookService discordWebhookService;
    private final Gson gson = new Gson();

    // Per-symbol locks（防止 WS 事件與信號平倉並發衝突）
    private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    // 連線狀態
    private volatile String listenKey;
    private volatile WebSocket webSocket;
    private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>(null);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile boolean connected = false;
    private volatile boolean alertSent = false;
    private volatile boolean shuttingDown = false;

    // 重連配置
    private static final long BASE_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 20;

    public BinanceUserDataStreamService(OkHttpClient httpClient,
                                         BinanceConfig binanceConfig,
                                         TradeRecordService tradeRecordService,
                                         DiscordWebhookService discordWebhookService) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.tradeRecordService = tradeRecordService;
        this.discordWebhookService = discordWebhookService;

        // WebSocket 專用 client：無 read timeout + 每 20 秒 ping
        this.wsClient = httpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 生命週期 ====================

    @PostConstruct
    public void init() {
        if (binanceConfig.getWsBaseUrl() == null || binanceConfig.getWsBaseUrl().isBlank()) {
            log.warn("WebSocket base URL 未設定，User Data Stream 功能停用");
            return;
        }
        try {
            startStream();
            log.info("Binance User Data Stream 啟動成功");
        } catch (Exception e) {
            log.error("User Data Stream 啟動失敗，將在背景重試: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("User Data Stream 正在關閉...");
        if (webSocket != null) {
            webSocket.close(1000, "shutdown");
        }
        deleteListenKey();
        log.info("User Data Stream 已關閉");
    }

    // ==================== listenKey 管理 ====================

    private void startStream() {
        this.listenKey = createListenKey();
        log.info("ListenKey 建立成功: {}...", listenKey.substring(0, Math.min(listenKey.length(), 20)));

        String wsUrl = binanceConfig.getWsBaseUrl() + listenKey;
        Request request = new Request.Builder().url(wsUrl).build();
        this.webSocket = wsClient.newWebSocket(request, new UserDataWebSocketListener());
    }

    /**
     * 建立 listenKey（只需 X-MBX-APIKEY，不需 HMAC 簽名）
     */
    private String createListenKey() {
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
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
     * 每 30 分鐘 PUT keepalive（listenKey 有效期 60 分鐘）
     */
    @Scheduled(fixedRate = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void keepAliveListenKey() {
        if (listenKey == null) return;
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.debug("ListenKey keepalive 成功");
            } else {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("ListenKey keepalive 失敗: {} {}", response.code(), body);
                if (response.code() == 400 || response.code() == 401) {
                    log.warn("ListenKey 可能已過期，嘗試重建連線...");
                    reconnect();
                }
            }
        } catch (Exception e) {
            log.error("ListenKey keepalive 異常: {}", e.getMessage());
        }
    }

    private void deleteListenKey() {
        if (listenKey == null) return;
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            log.info("ListenKey 已刪除: {}", response.isSuccessful());
        } catch (Exception e) {
            log.warn("刪除 listenKey 失敗: {}", e.getMessage());
        }
    }

    // ==================== WebSocket Listener ====================

    private class UserDataWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            connected = true;
            reconnectAttempts.set(0);
            lastMessageTime.set(Instant.now());
            log.info("User Data Stream WebSocket 已連線");

            if (alertSent) {
                alertSent = false;
                discordWebhookService.sendNotification(
                        "✅ Binance User Data Stream 已恢復",
                        "WebSocket 連線已重新建立\n自動觸發止損/止盈將正常同步至 DB",
                        DiscordWebhookService.COLOR_GREEN);
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            lastMessageTime.set(Instant.now());
            try {
                JsonObject json = gson.fromJson(text, JsonObject.class);
                String eventType = json.has("e") ? json.get("e").getAsString() : "";

                switch (eventType) {
                    case "ORDER_TRADE_UPDATE":
                        handleOrderTradeUpdate(json);
                        break;
                    case "ACCOUNT_UPDATE":
                        log.debug("ACCOUNT_UPDATE received (ignored)");
                        break;
                    case "listenKeyExpired":
                        log.warn("ListenKey 已過期，觸發重連...");
                        reconnect();
                        break;
                    default:
                        log.debug("Unknown user data event: {}", eventType);
                }
            } catch (Exception e) {
                log.error("處理 WebSocket 訊息失敗: {}", e.getMessage(), e);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            log.info("WebSocket closing: code={} reason={}", code, reason);
            connected = false;
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("WebSocket closed: code={} reason={}", code, reason);
            connected = false;
            if (!shuttingDown) {
                scheduleReconnect();
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("WebSocket failure: {}", t.getMessage());
            connected = false;
            if (!shuttingDown) {
                if (!alertSent) {
                    alertSent = true;
                    discordWebhookService.sendNotification(
                            "🚨 Binance User Data Stream 斷線",
                            "WebSocket 連線中斷: " + t.getMessage()
                                    + "\n自動觸發止損/止盈暫時無法同步至 DB"
                                    + "\n正在嘗試自動重連...",
                            DiscordWebhookService.COLOR_RED);
                }
                scheduleReconnect();
            }
        }
    }

    // ==================== 事件處理 ====================

    /**
     * 處理 ORDER_TRADE_UPDATE 事件
     * - FILLED 的 STOP_MARKET / TAKE_PROFIT_MARKET → 記錄平倉
     * - CANCELED / EXPIRED 的 STOP_MARKET / TAKE_PROFIT_MARKET → 告警保護消失
     */
    void handleOrderTradeUpdate(JsonObject event) {
        JsonObject order = event.getAsJsonObject("o");
        if (order == null) {
            log.warn("ORDER_TRADE_UPDATE missing 'o' field");
            return;
        }

        String symbol = order.get("s").getAsString();
        String orderType = order.get("o").getAsString();
        String orderStatus = order.get("X").getAsString();
        String orderId = String.valueOf(order.get("i").getAsLong());
        String side = order.get("S").getAsString();

        log.info("ORDER_TRADE_UPDATE: {} {} {} status={} orderId={}",
                symbol, side, orderType, orderStatus, orderId);

        // SL/TP 被取消或過期 → 持倉失去保護，緊急告警
        if (("CANCELED".equals(orderStatus) || "EXPIRED".equals(orderStatus))
                && ("STOP_MARKET".equals(orderType) || "TAKE_PROFIT_MARKET".equals(orderType))) {
            handleProtectionLost(symbol, orderType, orderId, orderStatus);
            return;
        }

        // 非 FILLED 的其他狀態（NEW, PARTIALLY_FILLED 等）→ 忽略
        if (!"FILLED".equals(orderStatus)) {
            log.debug("訂單未完全成交 ({}), 忽略", orderStatus);
            return;
        }

        double avgPrice = order.get("ap").getAsDouble();
        double filledQty = order.get("z").getAsDouble();
        double commission = order.get("n").getAsDouble();
        String commissionAsset = order.get("N").getAsString();
        double realizedProfit = order.get("rp").getAsDouble();
        long transactionTime = order.get("T").getAsLong();

        // 手續費幣種非 USDT 時用估算
        if (!"USDT".equals(commissionAsset)) {
            log.warn("手續費幣種非 USDT ({}), 使用估算: exitPrice × qty × 0.04%", commissionAsset);
            commission = avgPrice * filledQty * 0.0004;
        }

        switch (orderType) {
            case "STOP_MARKET":
                log.info("止損觸發: {} @ {} qty={} commission={} rp={}",
                        symbol, avgPrice, filledQty, commission, realizedProfit);
                processStreamClose(symbol, avgPrice, filledQty, commission,
                        realizedProfit, orderId, "SL_TRIGGERED", transactionTime);
                break;

            case "TAKE_PROFIT_MARKET":
                log.info("止盈觸發: {} @ {} qty={} commission={} rp={}",
                        symbol, avgPrice, filledQty, commission, realizedProfit);
                processStreamClose(symbol, avgPrice, filledQty, commission,
                        realizedProfit, orderId, "TP_TRIGGERED", transactionTime);
                break;

            case "LIMIT":
                log.info("LIMIT 訂單成交: {} {} @ {} qty={}", symbol, side, avgPrice, filledQty);
                break;

            case "MARKET":
                log.info("MARKET 訂單成交: {} {} @ {} qty={}", symbol, side, avgPrice, filledQty);
                break;

            default:
                log.debug("非關注訂單類型: {} {}", orderType, symbol);
        }
    }

    /**
     * SL/TP 被取消或過期 — 持倉失去保護
     * 可能原因：手動在幣安取消、掛單過期、系統 MOVE_SL 過程中
     *
     * 系統自己的 MOVE_SL / DCA 會先取消再重掛，所以這裡不需要自動重掛，
     * 只做告警 + 記錄，由使用者判斷是否需要手動處理。
     */
    private void handleProtectionLost(String symbol, String orderType, String orderId, String reason) {
        boolean isSL = "STOP_MARKET".equals(orderType);
        String label = isSL ? "止損" : "止盈";

        log.warn("⚠️ {} 被{}: {} orderId={}", label, reason, symbol, orderId);

        // 記錄到 DB
        try {
            tradeRecordService.recordProtectionLost(symbol, orderType, orderId, reason);
        } catch (Exception e) {
            log.error("記錄保護消失事件失敗: {}", e.getMessage());
        }

        // SL 被取消是高危事件（持倉裸奔），用紅色告警
        // TP 被取消影響較小，用黃色告警
        int color = isSL ? DiscordWebhookService.COLOR_RED : DiscordWebhookService.COLOR_YELLOW;
        String urgency = isSL ? "🚨" : "⚠️";

        discordWebhookService.sendNotification(
                urgency + " " + label + "單被取消",
                String.format("%s\n訂單號: %s\n原因: %s\n%s",
                        symbol, orderId, reason,
                        isSL ? "⚠️ 持倉已失去止損保護！請立即檢查" : "止盈保護已消失，止損仍有效"),
                color);
    }

    /**
     * 處理 SL/TP 觸發的平倉，使用 per-symbol 鎖保護
     */
    private void processStreamClose(String symbol, double exitPrice, double exitQty,
                                     double commission, double realizedProfit,
                                     String orderId, String exitReason, long transactionTime) {
        ReentrantLock lock = symbolLocks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            tradeRecordService.recordCloseFromStream(
                    symbol, exitPrice, exitQty, commission,
                    realizedProfit, orderId, exitReason, transactionTime);

            String emoji = "SL_TRIGGERED".equals(exitReason) ? "🛑" : "🎯";
            String label = "SL_TRIGGERED".equals(exitReason) ? "止損觸發" : "止盈觸發";
            discordWebhookService.sendNotification(
                    emoji + " " + label + " (自動)",
                    String.format("%s\n出場價: %.2f\n數量: %.4f\n手續費: %.4f USDT\n已實現損益: %.2f USDT",
                            symbol, exitPrice, exitQty, commission, realizedProfit),
                    "SL_TRIGGERED".equals(exitReason)
                            ? DiscordWebhookService.COLOR_RED
                            : DiscordWebhookService.COLOR_GREEN);

        } catch (Exception e) {
            log.error("WebSocket 平倉記錄失敗: {} {} - {}", symbol, exitReason, e.getMessage(), e);
            discordWebhookService.sendNotification(
                    "⚠️ WebSocket 平倉記錄失敗",
                    String.format("%s %s\norderId=%s\n錯誤: %s\n請手動檢查 DB",
                            symbol, exitReason, orderId, e.getMessage()),
                    DiscordWebhookService.COLOR_YELLOW);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 重連機制 ====================

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("WebSocket 重連次數已達上限 ({})，停止重試", MAX_RECONNECT_ATTEMPTS);
            discordWebhookService.sendNotification(
                    "🚨 User Data Stream 重連失敗",
                    String.format("已嘗試 %d 次重連，全部失敗\n請手動重啟服務！", MAX_RECONNECT_ATTEMPTS),
                    DiscordWebhookService.COLOR_RED);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
        log.info("WebSocket 重連排程: 第 {} 次嘗試，延遲 {}ms", attempt, delay);

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (!shuttingDown) {
                    reconnect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reconnectThread.setDaemon(true);
        reconnectThread.setName("ws-reconnect-" + attempt);
        reconnectThread.start();
    }

    private synchronized void reconnect() {
        try {
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "reconnecting");
                } catch (Exception e) {
                    log.debug("關閉舊 WebSocket 時出錯: {}", e.getMessage());
                }
            }
            deleteListenKey();
            startStream();
        } catch (Exception e) {
            log.error("重連失敗: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    // ==================== 狀態查詢 ====================

    public Map<String, Object> getStatus() {
        Instant lastMsg = lastMessageTime.get();
        long elapsed = lastMsg != null ? Instant.now().getEpochSecond() - lastMsg.getEpochSecond() : -1;
        return Map.of(
                "connected", connected,
                "listenKeyActive", listenKey != null,
                "lastMessageTime", lastMsg != null ? lastMsg.toString() : "never",
                "elapsedSeconds", elapsed,
                "reconnectAttempts", reconnectAttempts.get(),
                "alertSent", alertSent
        );
    }
}
