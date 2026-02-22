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

import java.util.List;
import java.util.Optional;
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

        manager = new MultiUserDataStreamManager(
                httpClient, binanceConfig, tradeRecordService, discordWebhookService,
                new SymbolLockRegistry(), userApiKeyService, userRepository);
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
            when(userApiKeyService.hasApiKey("u1")).thenReturn(true);
            when(userApiKeyService.hasApiKey("u2")).thenReturn(true);
            when(userApiKeyService.hasApiKey("u5")).thenReturn(false);  // 沒有 API Key

            // startUserStream 會呼叫 getUserBinanceKeys，但建立 listenKey 會失敗（mock 未設定 HTTP）
            // 這裡只驗證過濾邏輯：u1, u2 會嘗試啟動，u3, u4, u5 會被過濾掉
            when(userApiKeyService.getUserBinanceKeys("u1"))
                    .thenReturn(Optional.of(new BinanceKeys("key1", "secret1")));
            when(userApiKeyService.getUserBinanceKeys("u2"))
                    .thenReturn(Optional.of(new BinanceKeys("key2", "secret2")));

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
            UserStreamContext context = new UserStreamContext("u1", "key", "secret");

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(1);

            manager.scheduleReconnect("u1", context);
            assertThat(context.getReconnectAttempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("超過上限停止重試並發告警")
        void stopsAfterMaxAttempts() {
            UserStreamContext context = new UserStreamContext("u1", "key", "secret");

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
            UserStreamContext context = new UserStreamContext("u1", "key", "secret");

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

            UserStreamContext context = new UserStreamContext("u1", "key", "secret");
            manager.scheduleReconnect("u1", context);

            // shuttingDown=true，應直接 return，attempts 還是會加（但不排程）
            assertThat((Object) context.getPendingReconnect()).isNull();
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
}
