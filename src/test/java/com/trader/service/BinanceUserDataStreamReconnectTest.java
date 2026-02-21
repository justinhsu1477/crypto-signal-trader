package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.MultiUserDataStreamManager;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeRecordService;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 重連機制測試
 *
 * 覆蓋三個修復：
 * 1. selfInitiatedClose flag — onClosed 區分「自己關的」vs「被動斷開」
 * 2. ScheduledExecutorService 去重 — 多次 scheduleReconnect 只保留最後一個
 * 3. 120 秒心跳檢查已移除 — checkWebSocketHeartbeat 方法不存在
 */
class BinanceUserDataStreamReconnectTest {

    private OkHttpClient httpClient;
    private BinanceConfig binanceConfig;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService discordWebhookService;
    private MultiUserConfig multiUserConfig;
    private MultiUserDataStreamManager multiUserManager;
    private BinanceUserDataStreamService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
        tradeRecordService = mock(TradeRecordService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        multiUserConfig = mock(MultiUserConfig.class);
        multiUserManager = mock(MultiUserDataStreamManager.class);

        when(multiUserConfig.isEnabled()).thenReturn(false);

        // Mock wsClient builder chain
        OkHttpClient.Builder mockBuilder = mock(OkHttpClient.Builder.class);
        OkHttpClient mockWsClient = mock(OkHttpClient.class);
        when(httpClient.newBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.pingInterval(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockWsClient);

        service = new BinanceUserDataStreamService(
                httpClient, binanceConfig, tradeRecordService, discordWebhookService,
                new SymbolLockRegistry(), multiUserConfig, multiUserManager);
    }

    @AfterEach
    void tearDown() {
        // 確保 executor 關閉，避免 thread leak
        ScheduledExecutorService executor = service.getReconnectExecutor();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ==================== selfInitiatedClose flag ====================

    @Nested
    @DisplayName("selfInitiatedClose flag — 防止死循環")
    class SelfInitiatedCloseTests {

        @Test
        @DisplayName("初始狀態 selfInitiatedClose = false")
        void initialStateIsFalse() {
            assertThat(service.isSelfInitiatedClose()).isFalse();
        }

        @Test
        @DisplayName("scheduleReconnect 被動斷開時 — reconnectAttempts 遞增")
        void passiveDisconnectIncrementsAttempts() {
            // 模擬被動斷開：直接呼叫 scheduleReconnect（onClosed 內部呼叫的）
            service.scheduleReconnect();

            assertThat(service.getReconnectAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("超過 MAX_RECONNECT_ATTEMPTS 時停止重試並發告警")
        void stopsAfterMaxAttempts() {
            // 先呼叫 MAX_RECONNECT_ATTEMPTS 次
            for (int i = 0; i < BinanceUserDataStreamService.MAX_RECONNECT_ATTEMPTS; i++) {
                service.scheduleReconnect();
            }

            // 第 21 次應該被擋住
            service.scheduleReconnect();
            assertThat(service.getReconnectAttempts())
                    .isEqualTo(BinanceUserDataStreamService.MAX_RECONNECT_ATTEMPTS + 1);

            // 應該發送紅色告警通知
            verify(discordWebhookService).sendNotification(
                    contains("重連失敗"),
                    contains("手動重啟"),
                    eq(DiscordWebhookService.COLOR_RED));
        }
    }

    // ==================== ScheduledExecutorService 去重 ====================

    @Nested
    @DisplayName("ScheduledExecutorService — 排程去重")
    class SchedulerDeduplicationTests {

        @Test
        @DisplayName("多次 scheduleReconnect 只保留最後一個排程")
        void multipleScheduleKeepsOnlyLast() {
            // 快速連續呼叫 3 次
            service.scheduleReconnect();
            ScheduledFuture<?> first = service.getPendingReconnect();

            service.scheduleReconnect();
            ScheduledFuture<?> second = service.getPendingReconnect();

            service.scheduleReconnect();
            ScheduledFuture<?> third = service.getPendingReconnect();

            // 前面的應該被取消
            assertThat(first.isCancelled()).isTrue();
            assertThat(second.isCancelled()).isTrue();
            // 最後一個還在等待
            assertThat(third.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("shutdown 後 scheduleReconnect 不拋異常")
        void scheduleAfterShutdownDoesNotThrow() {
            service.getReconnectExecutor().shutdownNow();

            assertThatCode(() -> service.scheduleReconnect())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reconnectAttempts 在連續排程中正確遞增")
        void attemptsIncrementCorrectly() {
            service.scheduleReconnect();
            assertThat(service.getReconnectAttempts()).isEqualTo(1);

            service.scheduleReconnect();
            assertThat(service.getReconnectAttempts()).isEqualTo(2);

            service.scheduleReconnect();
            assertThat(service.getReconnectAttempts()).isEqualTo(3);
        }
    }

    // ==================== 指數退避延遲 ====================

    @Nested
    @DisplayName("指數退避計算")
    class ExponentialBackoffTests {

        @Test
        @DisplayName("延遲遵循 2^n 指數退避，上限 60 秒")
        void delayFollowsExponentialBackoff() {
            // 驗證計算公式 Math.min(BASE * 2^(attempt-1), MAX)
            long base = BinanceUserDataStreamService.BASE_RECONNECT_DELAY_MS;
            long max = BinanceUserDataStreamService.MAX_RECONNECT_DELAY_MS;

            assertThat(Math.min(base * (1L << 0), max)).isEqualTo(1000);   // attempt 1
            assertThat(Math.min(base * (1L << 1), max)).isEqualTo(2000);   // attempt 2
            assertThat(Math.min(base * (1L << 2), max)).isEqualTo(4000);   // attempt 3
            assertThat(Math.min(base * (1L << 3), max)).isEqualTo(8000);   // attempt 4
            assertThat(Math.min(base * (1L << 4), max)).isEqualTo(16000);  // attempt 5
            assertThat(Math.min(base * (1L << 5), max)).isEqualTo(32000);  // attempt 6
            assertThat(Math.min(base * (1L << 6), max)).isEqualTo(60000);  // attempt 7 (capped)
            assertThat(Math.min(base * (1L << 7), max)).isEqualTo(60000);  // attempt 8 (still capped)
        }
    }

    // ==================== 120 秒心跳檢查已移除 ====================

    @Nested
    @DisplayName("120 秒心跳檢查已移除")
    class HeartbeatRemovedTests {

        @Test
        @DisplayName("checkWebSocketHeartbeat 方法不存在")
        void heartbeatCheckMethodRemoved() {
            // 確認 checkWebSocketHeartbeat 方法已被移除
            assertThatThrownBy(() ->
                    BinanceUserDataStreamService.class.getDeclaredMethod("checkWebSocketHeartbeat"))
                    .isInstanceOf(NoSuchMethodException.class);
        }
    }

    // ==================== WebSocket Listener 行為模擬 ====================

    @Nested
    @DisplayName("WebSocket Listener 回呼行為")
    class WebSocketListenerTests {

        private WebSocketListener listener;

        @BeforeEach
        void extractListener() throws Exception {
            // 透過反射取得 inner class 的 Listener 實例
            // startStream 會建立 listener，但因為沒有真的 HTTP 連線，
            // 我們需要直接實例化 inner class
            Class<?>[] innerClasses = BinanceUserDataStreamService.class.getDeclaredClasses();
            Class<?> listenerClass = null;
            for (Class<?> c : innerClasses) {
                if (WebSocketListener.class.isAssignableFrom(c)) {
                    listenerClass = c;
                    break;
                }
            }
            assertThat(listenerClass).as("應找到 UserDataWebSocketListener inner class").isNotNull();

            // Inner class 需要外部類實例作為參數
            var constructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class);
            constructor.setAccessible(true);
            listener = (WebSocketListener) constructor.newInstance(service);
        }

        @Test
        @DisplayName("onOpen 重置 reconnectAttempts 為 0 並標記 connected=true")
        void onOpenResetsState() throws Exception {
            // 先製造一些 reconnect attempts
            service.scheduleReconnect();
            service.scheduleReconnect();
            assertThat(service.getReconnectAttempts()).isEqualTo(2);

            // 模擬連線成功
            WebSocket mockWs = mock(WebSocket.class);
            Response mockResponse = mock(Response.class);
            listener.onOpen(mockWs, mockResponse);

            assertThat(service.getReconnectAttempts()).isEqualTo(0);
            assertThat(service.isConnected()).isTrue();
        }

        @Test
        @DisplayName("onClosed + selfInitiatedClose=true → 不觸發 scheduleReconnect")
        void onClosedWithSelfInitiatedDoesNotReconnect() throws Exception {
            // 設定 selfInitiatedClose = true（模擬 reconnect() 執行中）
            Field selfInitField = BinanceUserDataStreamService.class.getDeclaredField("selfInitiatedClose");
            selfInitField.setAccessible(true);
            selfInitField.set(service, true);

            int attemptsBefore = service.getReconnectAttempts();

            // 模擬 onClosed
            WebSocket mockWs = mock(WebSocket.class);
            listener.onClosed(mockWs, 1000, "reconnecting");

            // reconnectAttempts 不應增加（沒呼叫 scheduleReconnect）
            assertThat(service.getReconnectAttempts()).isEqualTo(attemptsBefore);
        }

        @Test
        @DisplayName("onClosed + selfInitiatedClose=false → 觸發 scheduleReconnect")
        void onClosedWithPassiveDisconnectTriggersReconnect() throws Exception {
            // selfInitiatedClose 初始就是 false
            assertThat(service.isSelfInitiatedClose()).isFalse();

            int attemptsBefore = service.getReconnectAttempts();

            WebSocket mockWs = mock(WebSocket.class);
            listener.onClosed(mockWs, 1006, "abnormal closure");

            // 應觸發 scheduleReconnect → attempts +1
            assertThat(service.getReconnectAttempts()).isEqualTo(attemptsBefore + 1);
        }

        @Test
        @DisplayName("onClosed + shuttingDown=true → 不觸發 scheduleReconnect")
        void onClosedDuringShutdownDoesNotReconnect() throws Exception {
            Field shuttingDownField = BinanceUserDataStreamService.class.getDeclaredField("shuttingDown");
            shuttingDownField.setAccessible(true);
            shuttingDownField.set(service, true);

            int attemptsBefore = service.getReconnectAttempts();

            WebSocket mockWs = mock(WebSocket.class);
            listener.onClosed(mockWs, 1000, "shutdown");

            assertThat(service.getReconnectAttempts()).isEqualTo(attemptsBefore);
        }

        @Test
        @DisplayName("onFailure → 觸發 scheduleReconnect + 發送紅色告警")
        void onFailureTriggersReconnectAndAlert() throws Exception {
            WebSocket mockWs = mock(WebSocket.class);
            listener.onFailure(mockWs, new RuntimeException("Connection reset"), null);

            assertThat(service.getReconnectAttempts()).isEqualTo(1);
            assertThat(service.isConnected()).isFalse();

            verify(discordWebhookService).sendNotification(
                    contains("斷線"),
                    contains("Connection reset"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("onFailure 重複呼叫只發一次告警")
        void onFailureMultipleCallsSendsAlertOnce() throws Exception {
            WebSocket mockWs = mock(WebSocket.class);

            listener.onFailure(mockWs, new RuntimeException("error 1"), null);
            listener.onFailure(mockWs, new RuntimeException("error 2"), null);
            listener.onFailure(mockWs, new RuntimeException("error 3"), null);

            // alertSent flag 保護：只有第一次發紅色告警
            verify(discordWebhookService, times(1)).sendNotification(
                    contains("斷線"),
                    anyString(),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("onOpen 在 alertSent=true 時發送恢復通知")
        void onOpenSendsRecoveryNotificationWhenAlertWasSent() throws Exception {
            // 先觸發一次 failure 讓 alertSent = true
            WebSocket mockWs = mock(WebSocket.class);
            listener.onFailure(mockWs, new RuntimeException("disconnected"), null);

            // 然後連線成功
            Response mockResponse = mock(Response.class);
            listener.onOpen(mockWs, mockResponse);

            verify(discordWebhookService).sendNotification(
                    contains("已恢復"),
                    contains("重新建立"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }
    }

    // ==================== 死循環場景模擬 ====================

    @Nested
    @DisplayName("死循環場景 — 修復前會無限重連")
    class DeathLoopPreventionTests {

        @Test
        @DisplayName("reconnect() 觸發的 onClosed 不會再排重連（斷掉循環鏈）")
        void reconnectCloseDoesNotTriggerAnotherReconnect() throws Exception {
            // 取得 listener
            Class<?>[] innerClasses = BinanceUserDataStreamService.class.getDeclaredClasses();
            Class<?> listenerClass = null;
            for (Class<?> c : innerClasses) {
                if (WebSocketListener.class.isAssignableFrom(c)) {
                    listenerClass = c;
                    break;
                }
            }
            var constructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class);
            constructor.setAccessible(true);
            WebSocketListener listener = (WebSocketListener) constructor.newInstance(service);

            // 模擬 reconnect() 的行為：設定 selfInitiatedClose = true
            Field selfInitField = BinanceUserDataStreamService.class.getDeclaredField("selfInitiatedClose");
            selfInitField.setAccessible(true);
            selfInitField.set(service, true);

            // 模擬舊 socket 的 onClosed 回呼（這是死循環的關鍵環節）
            WebSocket mockWs = mock(WebSocket.class);
            listener.onClosed(mockWs, 1000, "reconnecting");

            // 驗證：attempts 不增加，沒有新排程
            assertThat(service.getReconnectAttempts()).isEqualTo(0);
            assertThat(service.getPendingReconnect()).isNull();
        }

        @Test
        @DisplayName("快速連續 5 次 scheduleReconnect — executor 中最多只有 1 個 pending task")
        void rapidScheduleOnlyOnePending() throws Exception {
            for (int i = 0; i < 5; i++) {
                service.scheduleReconnect();
            }

            ScheduledFuture<?> pending = service.getPendingReconnect();
            assertThat(pending).isNotNull();
            assertThat(pending.isCancelled()).isFalse();

            // attempts 應為 5（每次呼叫都 incrementAndGet），但 pending task 只有 1 個
            assertThat(service.getReconnectAttempts()).isEqualTo(5);
        }
    }
}
