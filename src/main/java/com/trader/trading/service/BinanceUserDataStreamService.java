package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.config.MultiUserConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final DiscordWebhookService discordWebhookService;
    private final MultiUserConfig multiUserConfig;
    private final MultiUserDataStreamManager multiUserManager;
    private final Gson gson = new Gson();
    private final OrderEventHandler orderEventHandler;

    // 連線狀態
    private volatile String listenKey;
    private volatile WebSocket webSocket;
    private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>(null);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile boolean connected = false;
    private volatile boolean alertSent = false;
    private volatile boolean shuttingDown = false;
    private volatile boolean selfInitiatedClose = false;       // 區分「自己關的」vs「被動斷開」

    // 重連排程：單執行緒，確保同時只有一個排程任務
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingReconnect;      // 追蹤當前排程，用於取消舊任務

    // 重連配置
    static final long BASE_RECONNECT_DELAY_MS = 1000;
    static final long MAX_RECONNECT_DELAY_MS = 60_000;
    static final int MAX_RECONNECT_ATTEMPTS = 20;

    public BinanceUserDataStreamService(OkHttpClient httpClient,
                                         BinanceConfig binanceConfig,
                                         TradeRecordService tradeRecordService,
                                         DiscordWebhookService discordWebhookService,
                                         SymbolLockRegistry symbolLockRegistry,
                                         MultiUserConfig multiUserConfig,
                                         MultiUserDataStreamManager multiUserManager) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.discordWebhookService = discordWebhookService;
        this.multiUserConfig = multiUserConfig;
        this.multiUserManager = multiUserManager;

        // 共用事件處理器（單用戶版 — 全局通知）
        this.orderEventHandler = new OrderEventHandler(
                tradeRecordService, symbolLockRegistry,
                discordWebhookService::sendNotification,
                gson, "");

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

        if (multiUserConfig.isEnabled()) {
            log.info("多用戶模式啟用，委派給 MultiUserDataStreamManager");
            multiUserManager.startAllStreams();
            return;
        }

        // 單用戶模式（舊系統）
        try {
            startStream();
            log.info("Binance User Data Stream 啟動成功（單用戶模式）");
        } catch (Exception e) {
            log.error("User Data Stream 啟動失敗，將在背景重試: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("User Data Stream 正在關閉...");

        if (multiUserConfig.isEnabled()) {
            multiUserManager.stopAllStreams();
        } else {
            cancelPendingReconnect();
            reconnectExecutor.shutdownNow();
            if (webSocket != null) {
                webSocket.close(1000, "shutdown");
            }
            deleteListenKey();
        }

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
        if (multiUserConfig.isEnabled()) {
            multiUserManager.keepAliveAll();
            return;
        }
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

    /*
     * 應用層心跳檢查已移除（方案 B）。
     *
     * 原本每 30 秒檢查「是否 120 秒沒訊息」，但 User Data Stream
     * 在沒有交易時本來就是安靜的，會造成大量誤判重連。
     *
     * 現在信任：
     * 1. OkHttp pingInterval(20s) — TCP 層存活偵測，斷線會觸發 onFailure
     * 2. listenKey keepalive PUT 30min — 400/401 表示 listenKey 失效，觸發 reconnect
     * 3. listenKeyExpired 事件 — Binance 主動通知 listenKey 過期
     */

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
                        orderEventHandler.handleOrderTradeUpdate(json);
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
            // 如果是自己發起的 close（reconnect / shutdown），不要再排重連
            if (selfInitiatedClose || shuttingDown) {
                log.debug("自發關閉，跳過 scheduleReconnect (selfInitiated={}, shuttingDown={})",
                        selfInitiatedClose, shuttingDown);
                return;
            }
            // 如果 ws 已經不是當前的 webSocket（reconnect 已建新連線），忽略舊的回呼
            if (ws != webSocket) {
                log.debug("舊 WebSocket 的 onClosed 回呼，已有新連線，忽略");
                return;
            }
            // 被動斷開（Binance server 關你、網路中斷等）→ 排程重連
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("WebSocket failure: {}", t.getMessage());
            connected = false;
            // 如果 ws 已經不是當前的 webSocket（reconnect 已建新連線），忽略舊的回呼
            if (ws != webSocket) {
                log.debug("舊 WebSocket 的 onFailure 回呼，已有新連線，忽略");
                return;
            }
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

    // ==================== 重連機制 ====================

    /**
     * 排程一次重連。
     * 使用 ScheduledExecutorService 取代裸 Thread：
     * - 每次排程前取消舊任務，確保同一時間只有一個 pending reconnect
     * - 避免多個觸發源（onClosed / onFailure / keepalive）同時排出多個重連
     */
    void scheduleReconnect() {
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

        // 取消舊的排程任務（如果有的話），確保不會疊加
        cancelPendingReconnect();

        try {
            pendingReconnect = reconnectExecutor.schedule(() -> {
                if (!shuttingDown) {
                    reconnect();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("重連排程被拒絕（executor 已關閉）");
        }
    }

    /**
     * 取消當前排程中的重連任務
     */
    private void cancelPendingReconnect() {
        ScheduledFuture<?> pending = this.pendingReconnect;
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
            log.debug("已取消舊的重連排程");
        }
    }

    /**
     * 執行重連：關閉舊 socket → 刪除 listenKey → 建立新 stream。
     * selfInitiatedClose flag 防止 onClosed() 把「自己關的」誤判為異常斷線。
     */
    synchronized void reconnect() {
        try {
            if (webSocket != null) {
                try {
                    selfInitiatedClose = true;  // 標記：接下來的 onClosed 是自己發起的
                    webSocket.close(1000, "reconnecting");
                } catch (Exception e) {
                    log.debug("關閉舊 WebSocket 時出錯: {}", e.getMessage());
                } finally {
                    // 給 OkHttp 一點時間回呼 onClosed，然後重置 flag
                    // 新 socket 的 onClosed 不應被跳過
                }
            }
            deleteListenKey();
            startStream();
            // startStream 成功後，新 socket 已建立，重置 flag
            // 新 socket 的 onClosed 應正常處理
            selfInitiatedClose = false;
        } catch (Exception e) {
            selfInitiatedClose = false;
            log.error("重連失敗: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    // ==================== 狀態查詢 ====================

    public Map<String, Object> getStatus() {
        if (multiUserConfig.isEnabled()) {
            return multiUserManager.getAllStatus();
        }

        Instant lastMsg = lastMessageTime.get();
        long elapsed = lastMsg != null ? Instant.now().getEpochSecond() - lastMsg.getEpochSecond() : -1;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", "single-user");
        status.put("connected", connected);
        status.put("listenKeyActive", listenKey != null);
        status.put("lastMessageTime", lastMsg != null ? lastMsg.toString() : "never");
        status.put("elapsedSeconds", elapsed);
        status.put("reconnectAttempts", reconnectAttempts.get());
        status.put("alertSent", alertSent);
        return status;
    }

    // ==================== 測試用 accessor（package-private）====================

    int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    boolean isSelfInitiatedClose() {
        return selfInitiatedClose;
    }

    boolean isConnected() {
        return connected;
    }

    ScheduledFuture<?> getPendingReconnect() {
        return pendingReconnect;
    }

    ScheduledExecutorService getReconnectExecutor() {
        return reconnectExecutor;
    }
}
