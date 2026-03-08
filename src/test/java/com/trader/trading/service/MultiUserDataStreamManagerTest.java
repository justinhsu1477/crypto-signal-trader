package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MultiUserDataStreamManager 單元測試
 *
 * 覆蓋：
 * - 啟動/停止所有用戶 stream
 * - 過濾條件（enabled, autoTradeEnabled, hasApiKey）
 * - per-user keepAlive 與 reconnect
 * - 排程去重
 * - 狀態查詢
 */
class MultiUserDataStreamManagerTest {

    private OkHttpClient httpClient;
    private ExchangeStreamProvider mockProvider;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService discordWebhookService;
    private UserApiKeyService userApiKeyService;
    private UserRepository userRepository;
    private MultiUserDataStreamManager manager;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        mockProvider = mock(ExchangeStreamProvider.class);
        tradeRecordService = mock(TradeRecordService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        userApiKeyService = mock(UserApiKeyService.class);
        userRepository = mock(UserRepository.class);

        // Mock wsClient builder chain
        OkHttpClient.Builder mockBuilder = mock(OkHttpClient.Builder.class);
        OkHttpClient mockWsClient = mock(OkHttpClient.class);
        when(httpClient.newBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.pingInterval(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockWsClient);

        // Mock provider returns "BINANCE"
        when(mockProvider.getExchangeName()).thenReturn("BINANCE");

        manager = new MultiUserDataStreamManager(
                httpClient, tradeRecordService, discordWebhookService,
                new SymbolLockRegistry(), userApiKeyService, userRepository,
                List.of(mockProvider));
    }

    @AfterEach
    void tearDown() {
        ScheduledExecutorService executor = manager.getReconnectExecutor();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ==================== 用戶過濾與啟動 ====================

    @Nested
    @DisplayName("startAllStreams — 用戶過濾")
    class StartAllStreamsTests {

        @Test
        @DisplayName("只啟動 enabled + autoTradeEnabled + hasApiKey 的用戶")
        void filtersUsersCorrectly() {
            User user1 = User.builder().userId("u1").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").enabled(true).autoTradeEnabled(true).build();
            User user3disabled = User.builder().userId("u3").enabled(false).autoTradeEnabled(true).build();
            User user4noAuto = User.builder().userId("u4").enabled(true).autoTradeEnabled(false).build();
            User user5noKey = User.builder().userId("u5").enabled(true).autoTradeEnabled(true).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3disabled, user4noAuto, user5noKey));

            // userId → exchange 映射
            when(userApiKeyService.getUserIdExchangeMap()).thenReturn(Map.of(
                    "u1", "BINANCE",
                    "u2", "BINANCE"));

            // Batch 模式：只有 u1, u2 有 API Key（u5 沒有，getAllExchangeKeys 不回傳）
            when(userApiKeyService.getAllExchangeKeys("BINANCE")).thenReturn(Map.of(
                    "u1", new ExchangeKeys("key1", "secret1"),
                    "u2", new ExchangeKeys("key2", "secret2")));

            // startAllStreams 會因為 provider.connect 失敗而進入 reconnect
            // 但 context 仍會被放入 activeStreams
            manager.startAllStreams();

            // 驗證只有 u1, u2 有 context（u3, u4 被過濾，u5 沒 key）
            assertThat(manager.getActiveStreams()).containsOnlyKeys("u1", "u2");
        }

        @Test
        @DisplayName("沒有符合條件的用戶 — activeStreams 為空")
        void noEligibleUsers() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(userApiKeyService.getUserIdExchangeMap()).thenReturn(Map.of());
            when(userApiKeyService.getAllExchangeKeys("BINANCE")).thenReturn(Map.of());

            manager.startAllStreams();

            assertThat(manager.getActiveStreams()).isEmpty();
        }
    }

    // ==================== startUserStream ====================

    @Nested
    @DisplayName("startUserStream — 單用戶啟動")
    class StartUserStreamTests {

        @Test
        @DisplayName("用戶無 API Key 時不建立 stream")
        void noApiKeySkips() {
            when(userApiKeyService.getUserPrimaryExchangeKeys("u1")).thenReturn(Optional.empty());

            manager.startUserStream("u1");

            assertThat(manager.getActiveStreams()).doesNotContainKey("u1");
        }

        @Test
        @DisplayName("重複呼叫同一用戶 — 跳過不重建")
        void duplicateStartSkips() {
            when(userApiKeyService.getUserPrimaryExchangeKeys("u1"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key1", "secret1"))));

            // 第一次啟動（會因 provider.connect mock 未完整設定進 reconnect，但 context 會存入 map）
            manager.startUserStream("u1");
            assertThat(manager.getActiveStreams()).containsKey("u1");

            // 第二次啟動 — 應跳過
            int sizeBeforeSecondCall = manager.getActiveStreams().size();
            manager.startUserStream("u1");
            assertThat(manager.getActiveStreams()).hasSize(sizeBeforeSecondCall);
        }
    }

    // ==================== stopUserStream ====================

    @Nested
    @DisplayName("stopUserStream — 停止與清理")
    class StopUserStreamTests {

        @Test
        @DisplayName("停止不存在的用戶 — 不拋異常")
        void stopNonExistentUserDoesNotThrow() {
            assertThatCode(() -> manager.stopUserStream("nonexistent"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("停止已存在的用戶 — 從 map 移除")
        void stopExistingUserRemovesFromMap() {
            when(userApiKeyService.getUserPrimaryExchangeKeys("u1"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key1", "secret1"))));

            manager.startUserStream("u1");
            assertThat(manager.getActiveStreams()).containsKey("u1");

            manager.stopUserStream("u1");
            assertThat(manager.getActiveStreams()).doesNotContainKey("u1");
        }
    }

    // ==================== stopAllStreams ====================

    @Nested
    @DisplayName("stopAllStreams — 全部關閉")
    class StopAllStreamsTests {

        @Test
        @DisplayName("停止所有 stream 並清空 map")
        void stopsAllAndClearsMap() {
            when(userApiKeyService.getUserPrimaryExchangeKeys(anyString()))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key", "secret"))));

            manager.startUserStream("u1");
            manager.startUserStream("u2");
            assertThat(manager.getActiveStreams()).hasSize(2);

            manager.stopAllStreams();

            assertThat(manager.getActiveStreams()).isEmpty();
            assertThat(manager.isShuttingDown()).isTrue();
        }
    }

    // ==================== 重連機制 ====================

    @Nested
    @DisplayName("scheduleReconnect — per-user 重連排程")
    class ReconnectTests {

        @Test
        @DisplayName("重連計數遞增")
        void reconnectIncrementsAttempts() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(1);

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("超過上限停止重試並發告警")
        void stopsAfterMaxAttempts() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");

            // 先衝到上限
            for (int i = 0; i < MultiUserDataStreamManager.MAX_RECONNECT_ATTEMPTS; i++) {
                manager.scheduleReconnect("u1", context);
            }

            // 再一次應該被擋住
            manager.scheduleReconnect("u1", context);

            verify(discordWebhookService).sendNotificationToUser(
                    eq("u1"),
                    contains("重連失敗"),
                    contains("管理員"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("排程去重 — 多次呼叫只保留最後一個 pending")
        void deduplicatesSchedule() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");

            manager.scheduleReconnect("u1", context);
            ScheduledFuture<?> first = context.getPendingReconnect();

            manager.scheduleReconnect("u1", context);
            ScheduledFuture<?> second = context.getPendingReconnect();

            manager.scheduleReconnect("u1", context);
            ScheduledFuture<?> third = context.getPendingReconnect();

            assertThat(first.isCancelled()).isTrue();
            assertThat(second.isCancelled()).isTrue();
            assertThat(third.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("shuttingDown 時不排程")
        void doesNotScheduleWhenShuttingDown() {
            manager.stopAllStreams();  // sets shuttingDown = true

            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            manager.scheduleReconnect("u1", context);

            // shuttingDown=true，應直接 return，attempts 還是會加（但不排程）
            assertThat((Object) context.getPendingReconnect()).isNull();
        }
    }

    // ==================== WebSocket Admin 通知 ====================

    @Nested
    @DisplayName("WebSocket Admin 通知")
    class WebSocketAdminNotificationTests {

        @Test
        @DisplayName("重連失敗同時通知 Admin")
        void reconnectFailureNotifiesAdmin() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");

            // 衝到上限 + 再呼叫一次
            for (int i = 0; i <= MultiUserDataStreamManager.MAX_RECONNECT_ATTEMPTS; i++) {
                manager.scheduleReconnect("u1", context);
            }

            // 驗證同時通知 Admin（帶 displayName）
            verify(discordWebhookService).sendNotificationToAdmins(
                    eq("User 1"),
                    contains("重連失敗"),
                    contains("管理員"),
                    eq(DiscordWebhookService.COLOR_RED));
        }
    }

    // ==================== 狀態查詢 ====================

    @Nested
    @DisplayName("狀態查詢")
    class StatusTests {

        @Test
        @DisplayName("getAllStatus 包含 mode 和 totalStreams")
        void allStatusContainsMetadata() {
            var status = manager.getAllStatus();

            assertThat(status.get("mode")).isEqualTo("multi-user");
            assertThat(status.get("totalStreams")).isEqualTo(0);
            assertThat(status.get("shuttingDown")).isEqualTo(false);
        }

        @Test
        @DisplayName("getUserStatus 不存在的用戶回傳 error")
        void userStatusNotFound() {
            var status = manager.getUserStatus("nonexistent");
            assertThat(status).containsKey("error");
        }
    }

    // ==================== 指數退避計算 ====================

    @Nested
    @DisplayName("指數退避配置")
    class BackoffConfig {

        @Test
        @DisplayName("配置常數與單用戶服務一致")
        void configMatchesSingleUserService() {
            assertThat(MultiUserDataStreamManager.BASE_RECONNECT_DELAY_MS)
                    .isEqualTo(BinanceUserDataStreamService.BASE_RECONNECT_DELAY_MS);
            assertThat(MultiUserDataStreamManager.MAX_RECONNECT_DELAY_MS)
                    .isEqualTo(BinanceUserDataStreamService.MAX_RECONNECT_DELAY_MS);
            assertThat(MultiUserDataStreamManager.MAX_RECONNECT_ATTEMPTS)
                    .isEqualTo(BinanceUserDataStreamService.MAX_RECONNECT_ATTEMPTS);
        }
    }

    // ==================== reconnect 修復驗證 ====================

    @Nested
    @DisplayName("reconnect — 重連邏輯（含修復）")
    class ReconnectLogicTests {

        @Test
        @DisplayName("reconnect 用戶不在 activeStreams → 跳過不拋異常")
        void reconnectNonExistentUserSkips() {
            assertThatCode(() -> manager.reconnect("nonexistent"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reconnect 期間 API Key 已消失 → 移除 stream")
        void reconnectApiKeyGoneRemovesStream() {
            // 先把 context 手動放入 activeStreams
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "old-key", "old-secret");
            manager.getActiveStreams().put("u1", context);

            when(userApiKeyService.getUserPrimaryExchangeKeys("u1")).thenReturn(Optional.empty());

            manager.reconnect("u1");

            assertThat(manager.getActiveStreams()).doesNotContainKey("u1");
        }

        @Test
        @DisplayName("reconnect 期間 API Key 已更新 → context 使用新 key")
        void reconnectWithUpdatedApiKey() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "old-key", "old-secret");
            manager.getActiveStreams().put("u1", context);

            when(userApiKeyService.getUserPrimaryExchangeKeys("u1"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("new-key", "new-secret"))));

            // provider.connect 會失敗（未完整設定），但 context API Key 應已更新
            manager.reconnect("u1");

            assertThat(context.getApiKey()).isEqualTo("new-key");
            assertThat(context.getSecretKey()).isEqualTo("new-secret");
        }
    }

    // ==================== keepAliveAll ====================

    @Nested
    @DisplayName("keepAliveAll — listenKey 續命")
    class KeepAliveTests {

        @Test
        @DisplayName("沒有 listenKey 的 context → provider keepAlive 帶 null")
        void skipsContextWithoutListenKey() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            // listenKey = null（預設）
            manager.getActiveStreams().put("u1", context);

            when(mockProvider.keepAlive(anyString(), any())).thenReturn(200);

            assertThatCode(() -> manager.keepAliveAll())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("keepalive 回 200 → 不觸發 reconnect")
        void successfulKeepAliveDoesNotReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            when(mockProvider.keepAlive("key", "test-listen-key")).thenReturn(200);

            manager.keepAliveAll();

            // 不應排程重連
            assertThat((Object) context.getPendingReconnect()).isNull();
        }

        @Test
        @DisplayName("keepalive 回 400 → 設 selfInitiatedClose + 觸發 reconnect")
        void keepAlive400TriggersSelfInitiatedAndReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            when(mockProvider.keepAlive("key", "test-listen-key")).thenReturn(400);

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).scheduleReconnect(anyString(), any(UserStreamContext.class));

            spyManager.keepAliveAll();

            // Issue 3 修復：應先設 selfInitiatedClose
            assertThat(context.isSelfInitiatedClose()).isTrue();
            verify(spyManager).scheduleReconnect(eq("u1"), eq(context));
        }

        @Test
        @DisplayName("keepalive 回 401 → 設 selfInitiatedClose + 觸發 reconnect")
        void keepAlive401TriggersSelfInitiatedAndReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            when(mockProvider.keepAlive("key", "test-listen-key")).thenReturn(401);

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).scheduleReconnect(anyString(), any(UserStreamContext.class));

            spyManager.keepAliveAll();

            assertThat(context.isSelfInitiatedClose()).isTrue();
            verify(spyManager).scheduleReconnect(eq("u1"), eq(context));
        }

        @Test
        @DisplayName("keepalive 異常 → 不拋異常不 reconnect")
        void keepAliveExceptionDoesNotThrow() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            when(mockProvider.keepAlive("key", "test-listen-key"))
                    .thenThrow(new RuntimeException("Network error"));

            assertThatCode(() -> manager.keepAliveAll())
                    .doesNotThrowAnyException();
        }
    }

    // ==================== stopAllStreams 進階 ====================

    @Nested
    @DisplayName("stopAllStreams — 進階驗證")
    class StopAllAdvanced {

        @Test
        @DisplayName("stopAllStreams 關閉 reconnect executor")
        void stopsReconnectExecutor() {
            // 先觸發 executor 建立
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            manager.scheduleReconnect("u1", context);

            ScheduledExecutorService executor = manager.getReconnectExecutor();
            assertThat(executor).isNotNull();
            assertThat(executor.isShutdown()).isFalse();

            manager.stopAllStreams();

            // executor 應該被 shutdown
            assertThat(manager.getReconnectExecutor()).isNull();
        }

        @Test
        @DisplayName("stopAllStreams 後 startAllStreams 重置 shuttingDown")
        void startAfterStopResetsShuttingDown() {
            manager.stopAllStreams();
            assertThat(manager.isShuttingDown()).isTrue();

            when(userRepository.findAll()).thenReturn(List.of());
            when(userApiKeyService.getUserIdExchangeMap()).thenReturn(Map.of());
            when(userApiKeyService.getAllExchangeKeys("BINANCE")).thenReturn(Map.of());
            manager.startAllStreams();

            assertThat(manager.isShuttingDown()).isFalse();
        }
    }

    // ==================== startAllStreams 進階 ====================

    @Nested
    @DisplayName("startAllStreams — 進階驗證")
    class StartAllAdvanced {

        @Test
        @DisplayName("單一用戶啟動失敗不影響其他用戶")
        void oneUserFailureDoesNotAffectOthers() {
            User user1 = User.builder().userId("u1").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").enabled(true).autoTradeEnabled(true).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2));

            // userId → exchange 映射
            when(userApiKeyService.getUserIdExchangeMap()).thenReturn(Map.of(
                    "u1", "BINANCE",
                    "u2", "BINANCE"));

            // Batch 模式：兩個用戶都有 API Key
            when(userApiKeyService.getAllExchangeKeys("BINANCE")).thenReturn(Map.of(
                    "u1", new ExchangeKeys("key1", "secret1"),
                    "u2", new ExchangeKeys("key2", "secret2")));

            manager.startAllStreams();

            // 兩個都應該在 activeStreams（即使 provider.connect 失敗，也會放入 map + reconnect）
            assertThat(manager.getActiveStreams()).containsKey("u1");
            assertThat(manager.getActiveStreams()).containsKey("u2");
        }
    }

    // ==================== 狀態查詢進階 ====================

    @Nested
    @DisplayName("狀態查詢 — 進階")
    class StatusAdvanced {

        @Test
        @DisplayName("有 active streams 時 getAllStatus 包含每個用戶的 status")
        void allStatusIncludesPerUserStatus() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setConnected(true);
            context.setListenKey("test-key");
            manager.getActiveStreams().put("u1", context);

            var status = manager.getAllStatus();

            assertThat(status.get("totalStreams")).isEqualTo(1);
            @SuppressWarnings("unchecked")
            var streams = (java.util.Map<String, Object>) status.get("streams");
            assertThat(streams).containsKey("u1");
        }

        @Test
        @DisplayName("getUserStatus 存在的用戶回傳完整 status")
        void userStatusFound() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "BINANCE", "key", "secret");
            context.setConnected(true);
            manager.getActiveStreams().put("u1", context);

            var status = manager.getUserStatus("u1");

            assertThat(status).containsEntry("userId", "u1");
            assertThat(status).containsEntry("connected", true);
        }
    }
}
