package com.trader.trading.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
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
    private BinanceConfig binanceConfig;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService discordWebhookService;
    private UserApiKeyService userApiKeyService;
    private UserRepository userRepository;
    private MultiUserDataStreamManager manager;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
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

        when(binanceConfig.getBaseUrl()).thenReturn("https://fapi.binance.com");
        when(binanceConfig.getWsBaseUrl()).thenReturn("wss://fstream.binance.com/ws/");

        BinanceFuturesService binanceFuturesService = mock(BinanceFuturesService.class);
        manager = new MultiUserDataStreamManager(
                httpClient, binanceConfig, tradeRecordService, discordWebhookService,
                new SymbolLockRegistry(), userApiKeyService, userRepository, binanceFuturesService);
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

            // Batch 模式：只有 u1, u2 有 API Key（u5 沒有，getAllBinanceKeys 不回傳）
            when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(Map.of(
                    "u1", new BinanceKeys("key1", "secret1"),
                    "u2", new BinanceKeys("key2", "secret2")));

            // startAllStreams 會因為 createListenKey HTTP call 失敗而進入 reconnect
            // 但 context 仍會被放入 activeStreams
            manager.startAllStreams();

            // 驗證只有 u1, u2 有 context（u3, u4 被過濾，u5 沒 key）
            assertThat(manager.getActiveStreams()).containsOnlyKeys("u1", "u2");
        }

        @Test
        @DisplayName("沒有符合條件的用戶 — activeStreams 為空")
        void noEligibleUsers() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(Map.of());

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
            when(userApiKeyService.getUserBinanceKeys("u1")).thenReturn(Optional.empty());

            manager.startUserStream("u1");

            assertThat(manager.getActiveStreams()).doesNotContainKey("u1");
        }

        @Test
        @DisplayName("重複呼叫同一用戶 — 跳過不重建")
        void duplicateStartSkips() {
            when(userApiKeyService.getUserBinanceKeys("u1"))
                    .thenReturn(Optional.of(new BinanceKeys("key1", "secret1")));

            // 第一次啟動（會因 HTTP mock 失敗進 reconnect，但 context 會存入 map）
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
            when(userApiKeyService.getUserBinanceKeys("u1"))
                    .thenReturn(Optional.of(new BinanceKeys("key1", "secret1")));

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
            when(userApiKeyService.getUserBinanceKeys(anyString()))
                    .thenReturn(Optional.of(new BinanceKeys("key", "secret")));

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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(1);

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("超過上限停止重試並發告警")
        void stopsAfterMaxAttempts() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");

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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");

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

            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            manager.scheduleReconnect("u1", context);

            // shuttingDown=true，應直接 return，attempts 還是會加（但不排程）
            assertThat((Object) context.getPendingReconnect()).isNull();
        }
    }

    // ==================== Give-up 狀態（達上限後） ====================

    @Nested
    @DisplayName("達上限 give-up 狀態")
    class GiveUpStateTests {

        @Test
        @DisplayName("達上限 → context.giveUp=true + listenKey 被清掉")
        void reachesLimitEntersGiveUp() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setListenKey("old-dead-key");
            manager.getActiveStreams().put("u1", context);

            // 衝到上限 + 再呼叫觸發 give-up
            for (int i = 0; i <= MultiUserDataStreamManager.MAX_RECONNECT_ATTEMPTS; i++) {
                manager.scheduleReconnect("u1", context);
            }

            assertThat(context.isGiveUp()).isTrue();
            assertThat(context.getListenKey()).isNull();
        }

        @Test
        @DisplayName("已 giveUp 的 stream 再呼叫 scheduleReconnect → 不加計數、不重複警報")
        void giveUpStreamIgnoresFurtherReconnects() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setGiveUp(true);
            int baseline = context.getReconnectAttempts();

            // 清空先前可能的 mock 呼叫
            clearInvocations(discordWebhookService);

            manager.scheduleReconnect("u1", context);
            manager.scheduleReconnect("u1", context);

            // 計數不變、不再發送任何警報
            assertThat(context.getReconnectAttempts()).isEqualTo(baseline);
            verify(discordWebhookService, never()).sendNotificationToUser(
                    anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("resetOnConnected 會清除 giveUp flag")
        void resetOnConnectedClearsGiveUp() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setGiveUp(true);

            context.resetOnConnected();

            assertThat(context.isGiveUp()).isFalse();
        }
    }

    // ==================== Recovery Scheduler ====================

    @Nested
    @DisplayName("recoverGaveUpStreams — 週期性恢復")
    class RecoverGaveUpStreamsTests {

        @Test
        @DisplayName("沒有 give-up stream → 靜默返回不呼叫 reconnect")
        void noGiveUpStreamsSilentReturn() {
            UserStreamContext healthy = new UserStreamContext("u1", "User 1", "key", "secret");
            healthy.setListenKey("healthy-key");
            manager.getActiveStreams().put("u1", healthy);

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).reconnect(anyString());

            spyManager.recoverGaveUpStreams();

            verify(spyManager, never()).reconnect(anyString());
            assertThat(healthy.isGiveUp()).isFalse();
        }

        @Test
        @DisplayName("give-up stream 會 reset giveUp+attempts 並呼叫 reconnect")
        void recoversGiveUpStreams() {
            UserStreamContext giveUpCtx = new UserStreamContext("u1", "User 1", "key", "secret");
            giveUpCtx.setGiveUp(true);
            // 模擬已累積 21 次
            for (int i = 0; i < 21; i++) giveUpCtx.incrementReconnectAttempts();
            manager.getActiveStreams().put("u1", giveUpCtx);

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).reconnect(anyString());

            spyManager.recoverGaveUpStreams();

            assertThat(giveUpCtx.isGiveUp()).isFalse();
            assertThat(giveUpCtx.getReconnectAttempts()).isEqualTo(0);
            verify(spyManager).reconnect("u1");
        }

        @Test
        @DisplayName("shuttingDown 時不做任何事")
        void skipsWhenShuttingDown() {
            UserStreamContext giveUpCtx = new UserStreamContext("u1", "User 1", "key", "secret");
            giveUpCtx.setGiveUp(true);
            manager.getActiveStreams().put("u1", giveUpCtx);

            manager.stopAllStreams();  // sets shuttingDown = true

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).reconnect(anyString());

            spyManager.recoverGaveUpStreams();

            verify(spyManager, never()).reconnect(anyString());
            assertThat(giveUpCtx.isGiveUp()).isTrue();  // 未被恢復
        }

        @Test
        @DisplayName("只處理 give-up stream，健康的 stream 不動")
        void onlyProcessesGiveUpStreams() {
            UserStreamContext healthy = new UserStreamContext("u1", "Healthy", "k1", "s1");
            healthy.setListenKey("healthy-key");
            UserStreamContext giveUp = new UserStreamContext("u2", "GiveUp", "k2", "s2");
            giveUp.setGiveUp(true);

            manager.getActiveStreams().put("u1", healthy);
            manager.getActiveStreams().put("u2", giveUp);

            MultiUserDataStreamManager spyManager = spy(manager);
            doNothing().when(spyManager).reconnect(anyString());

            spyManager.recoverGaveUpStreams();

            verify(spyManager, never()).reconnect("u1");
            verify(spyManager).reconnect("u2");
        }
    }

    // ==================== keepAliveAll give-up 互動 ====================

    @Nested
    @DisplayName("keepAliveAll — give-up stream 互動")
    class KeepAliveGiveUpTests {

        @Test
        @DisplayName("give-up 的 stream 會被 keepAliveAll 跳過（不發 HTTP PUT）")
        void keepAliveSkipsGiveUpStreams() {
            UserStreamContext giveUpCtx = new UserStreamContext("u1", "User 1", "key", "secret");
            giveUpCtx.setListenKey("some-key");  // 即使有 listenKey 也應跳過
            giveUpCtx.setGiveUp(true);
            manager.getActiveStreams().put("u1", giveUpCtx);

            MultiUserDataStreamManager spyManager = spy(manager);

            spyManager.keepAliveAll();

            // 被跳過，不應呼叫 keepAliveListenKey
            verify(spyManager, never()).keepAliveListenKey(anyString(), anyString());
        }
    }

    // ==================== WebSocket Admin 通知 ====================

    @Nested
    @DisplayName("WebSocket Admin 通知")
    class WebSocketAdminNotificationTests {

        @Test
        @DisplayName("重連失敗同時通知 Admin")
        void reconnectFailureNotifiesAdmin() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");

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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "old-key", "old-secret");
            manager.getActiveStreams().put("u1", context);

            when(userApiKeyService.getUserBinanceKeys("u1")).thenReturn(Optional.empty());

            manager.reconnect("u1");

            assertThat(manager.getActiveStreams()).doesNotContainKey("u1");
        }

        @Test
        @DisplayName("reconnect 期間 API Key 已更新 → context 使用新 key")
        void reconnectWithUpdatedApiKey() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "old-key", "old-secret");
            manager.getActiveStreams().put("u1", context);

            when(userApiKeyService.getUserBinanceKeys("u1"))
                    .thenReturn(Optional.of(new BinanceKeys("new-key", "new-secret")));

            // createListenKey 會失敗（HTTP mock 未完整設定），但 context API Key 應已更新
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
        @DisplayName("沒有 listenKey 的 context → 跳過")
        void skipsContextWithoutListenKey() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            // listenKey = null（預設）
            manager.getActiveStreams().put("u1", context);

            assertThatCode(() -> manager.keepAliveAll())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("keepalive 回 200 → 不觸發 reconnect")
        void successfulKeepAliveDoesNotReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            // 使用 spy 來驗證 keepAliveListenKey 的行為
            MultiUserDataStreamManager spyManager = spy(manager);
            doReturn(200).when(spyManager).keepAliveListenKey(anyString(), anyString());

            spyManager.keepAliveAll();

            // 不應排程重連
            assertThat((Object) context.getPendingReconnect()).isNull();
        }

        @Test
        @DisplayName("keepalive 回 400 → 設 selfInitiatedClose + 觸發 reconnect")
        void keepAlive400TriggersSelfInitiatedAndReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            MultiUserDataStreamManager spyManager = spy(manager);
            doReturn(400).when(spyManager).keepAliveListenKey(anyString(), anyString());
            doNothing().when(spyManager).scheduleReconnect(anyString(), any(UserStreamContext.class));

            spyManager.keepAliveAll();

            // Issue 3 修復：應先設 selfInitiatedClose
            assertThat(context.isSelfInitiatedClose()).isTrue();
            verify(spyManager).scheduleReconnect(eq("u1"), eq(context));
        }

        @Test
        @DisplayName("keepalive 回 401 → 設 selfInitiatedClose + 觸發 reconnect")
        void keepAlive401TriggersSelfInitiatedAndReconnect() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            MultiUserDataStreamManager spyManager = spy(manager);
            doReturn(401).when(spyManager).keepAliveListenKey(anyString(), anyString());
            doNothing().when(spyManager).scheduleReconnect(anyString(), any(UserStreamContext.class));

            spyManager.keepAliveAll();

            assertThat(context.isSelfInitiatedClose()).isTrue();
            verify(spyManager).scheduleReconnect(eq("u1"), eq(context));
        }

        @Test
        @DisplayName("keepalive 異常 → 不拋異常不 reconnect")
        void keepAliveExceptionDoesNotThrow() {
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setListenKey("test-listen-key");
            manager.getActiveStreams().put("u1", context);

            MultiUserDataStreamManager spyManager = spy(manager);
            doThrow(new RuntimeException("Network error"))
                    .when(spyManager).keepAliveListenKey(anyString(), anyString());

            assertThatCode(() -> spyManager.keepAliveAll())
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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
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
            when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(Map.of());
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

            // Batch 模式：兩個用戶都有 API Key
            when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(Map.of(
                    "u1", new BinanceKeys("key1", "secret1"),
                    "u2", new BinanceKeys("key2", "secret2")));

            manager.startAllStreams();

            // 兩個都應該在 activeStreams（即使 createListenKey 失敗，也會放入 map + reconnect）
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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
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
            UserStreamContext context = new UserStreamContext("u1", "User 1", "key", "secret");
            context.setConnected(true);
            manager.getActiveStreams().put("u1", context);

            var status = manager.getUserStatus("u1");

            assertThat(status).containsEntry("userId", "u1");
            assertThat(status).containsEntry("connected", true);
        }
    }
}
