package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.service.SignalSourceService;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.service.UserApiKeyService;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BroadcastTradeService 訊號來源路由測試
 *
 * 專注測試 channelId/guildId → SignalSourceService → 過濾綁定用戶 的路由邏輯。
 * 覆蓋：來源路由、fallback 全量廣播、空綁定用戶、targetUserIds 優先、skippedNotAssigned 計數。
 */
class BroadcastTradeServiceSourceRoutingTest {

    private UserRepository userRepository;
    private BinanceFuturesService binanceFuturesService;
    private NotificationService discordWebhookService;
    private UserApiKeyService userApiKeyService;
    private SubscriptionRepository subscriptionRepository;
    private SignalScoringService signalScoringService;
    private SignalSourceService signalSourceService;
    private TradeRepository tradeRepository;
    private BroadcastLogRepository broadcastLogRepository;
    private ObjectMapper objectMapper;
    private ExecutorService broadcastExecutor;

    private BroadcastTradeService service;

    // 共用測試用戶
    private User user1;
    private User user2;
    private User user3;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        discordWebhookService = mock(NotificationService.class);
        userApiKeyService = mock(UserApiKeyService.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        signalScoringService = mock(SignalScoringService.class);
        signalSourceService = mock(SignalSourceService.class);
        tradeRepository = mock(TradeRepository.class);
        broadcastLogRepository = mock(BroadcastLogRepository.class);
        objectMapper = new ObjectMapper();
        broadcastExecutor = Executors.newFixedThreadPool(2);

        service = new BroadcastTradeService(
                userRepository,
                binanceFuturesService,
                discordWebhookService,
                userApiKeyService,
                subscriptionRepository,
                signalScoringService,
                signalSourceService,
                tradeRepository,
                broadcastLogRepository,
                objectMapper,
                broadcastExecutor,
                15,
                0L);

        // 預設 AI 評分 — 非同步，立即返回 null
        when(signalScoringService.scoreAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 共用測試用戶
        user1 = User.builder().userId("u1").email("a@test.com").name("A")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
        user2 = User.builder().userId("u2").email("b@test.com").name("B")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
        user3 = User.builder().userId("u3").email("c@test.com").name("C")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
    }

    @AfterEach
    void tearDown() {
        broadcastExecutor.shutdownNow();
    }

    private TradeRequest createRequest(String action, String symbol, String side) {
        TradeRequest req = new TradeRequest();
        req.setAction(action);
        req.setSymbol(symbol);
        req.setSide(side);
        req.setEntryPrice(50000.0);
        req.setStopLoss(49000.0);
        req.setTakeProfit(52000.0);
        return req;
    }

    /**
     * 設定所有用戶都通過前置篩選（訂閱 + API Key）
     */
    private void setupAllUsersPassPreFilter(User... users) {
        List<User> userList = List.of(users);
        List<String> userIds = userList.stream().map(User::getUserId).toList();

        when(userRepository.findAll()).thenReturn(userList);
        when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(userIds);
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(new HashSet<>(userIds));
        when(binanceFuturesService.executeSignalForBroadcast(any(), anyString()))
                .thenReturn(List.of());
    }

    // ======================== 來源路由 ========================

    @Nested
    @DisplayName("訊號來源路由 (Source Routing)")
    class SourceRoutingTests {

        @Test
        @DisplayName("有 channelId + 來源匹配 + 綁定用戶 → 只對綁定用戶廣播")
        void sourceWithBoundUsers_onlyBoundUsersReceiveBroadcast() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            // u1, u2 綁定此來源; u3 未綁定
            when(signalSourceService.resolveTargetUserIds("ch-123", "g-456"))
                    .thenReturn(Optional.of(Set.of("u1", "u2")));
            when(signalSourceService.resolveSourceId("ch-123", "g-456"))
                    .thenReturn(Optional.of(1L));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-123").guildId("g-456").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(1); // u3

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u2"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u3"));
        }

        @Test
        @DisplayName("有 channelId + 無匹配來源 → 全量廣播（向下相容）")
        void noMatchingSource_fallbackToAllUsers() {
            setupAllUsersPassPreFilter(user1, user2);

            // 無匹配來源 → Optional.empty()
            when(signalSourceService.resolveTargetUserIds("unknown-ch", "unknown-g"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("unknown-ch", "unknown-g"))
                    .thenReturn(Optional.empty());

            TradeRequest request = createRequest("ENTRY", "ETHUSDT", "SHORT");
            request.setSource(SignalSource.builder()
                    .channelId("unknown-ch").guildId("unknown-g").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u2"));
        }

        @Test
        @DisplayName("來源匹配但綁定用戶為空 → 無用戶接收廣播")
        void sourceFoundButNoBoundUsers_noUsersReceiveBroadcast() {
            setupAllUsersPassPreFilter(user1, user2);

            // 來源匹配，但無綁定用戶
            when(signalSourceService.resolveTargetUserIds("ch-empty", "g-empty"))
                    .thenReturn(Optional.of(Set.of())); // 空 Set
            when(signalSourceService.resolveSourceId("ch-empty", "g-empty"))
                    .thenReturn(Optional.of(2L));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-empty").guildId("g-empty").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(2); // u1, u2 都被排除

            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), anyString());
        }
    }

    // ======================== GLOBAL 路由模式 ========================

    @Nested
    @DisplayName("GLOBAL 路由模式 (Global Routing)")
    class GlobalRoutingTests {

        @Test
        @DisplayName("GLOBAL 來源 → resolveTargetUserIds 回傳 empty → 全量廣播給所有人")
        void globalSource_broadcastsToAllUsers() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            // GLOBAL mode 回傳 Optional.empty() — 與「無匹配來源」相同語意
            when(signalSourceService.resolveTargetUserIds("ch-global", "g-global"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-global", "g-global"))
                    .thenReturn(Optional.of(10L));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-global").guildId("g-global").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(3);
            assertThat(result.get("successCount")).isEqualTo(3);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u2"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u3"));
        }
    }

    // ======================== targetUserIds 優先 ========================

    @Nested
    @DisplayName("targetUserIds 優先權 (Priority over Source Routing)")
    class TargetUserIdsPriorityTests {

        @Test
        @DisplayName("同時有 targetUserIds 和 channelId → targetUserIds 優先，跳過來源路由")
        void targetUserIdsTakesPriorityOverSourceRouting() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setTargetUserIds(List.of("u2")); // 指定 u2
            request.setSource(SignalSource.builder()
                    .channelId("ch-123").guildId("g-456").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0); // 來源路由被跳過
            assertThat(result.get("skippedNotTargeted")).isEqualTo(2); // u1, u3

            // 只對 u2 執行
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u2"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u3"));

            // signalSourceService 不應被呼叫（來源路由被跳過）
            verify(signalSourceService, never()).resolveTargetUserIds(anyString(), anyString());
        }
    }

    // ======================== 無來源資訊 ========================

    @Nested
    @DisplayName("無來源資訊 (No Source Info)")
    class NoSourceInfoTests {

        @Test
        @DisplayName("source = null → 全量廣播（向下相容）")
        void nullSource_broadcastToAllUsers() {
            setupAllUsersPassPreFilter(user1, user2);

            TradeRequest request = createRequest("CLOSE", "BTCUSDT", null);
            request.setSource(null);

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);

            verify(signalSourceService, never()).resolveTargetUserIds(anyString(), anyString());
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u2"));
        }

        @Test
        @DisplayName("source 有值但 channelId = null → 全量廣播（向下相容）")
        void sourceWithoutChannelId_broadcastToAllUsers() {
            setupAllUsersPassPreFilter(user1, user2);

            TradeRequest request = createRequest("ENTRY", "ETHUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .platform("MANUAL").authorName("Admin").build()); // 無 channelId

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);

            verify(signalSourceService, never()).resolveTargetUserIds(anyString(), anyString());
        }
    }

    // ======================== skippedNotAssigned 計數 ========================

    @Nested
    @DisplayName("skippedNotAssigned 計數正確性")
    class SkippedNotAssignedCountTests {

        @Test
        @DisplayName("3 個活躍用戶 + 1 個綁定 → skippedNotAssigned = 2")
        void correctSkippedCount() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            // 只有 u1 綁定
            when(signalSourceService.resolveTargetUserIds("ch-one", "g-one"))
                    .thenReturn(Optional.of(Set.of("u1")));
            when(signalSourceService.resolveSourceId("ch-one", "g-one"))
                    .thenReturn(Optional.of(3L));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-one").guildId("g-one").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(2); // u2, u3

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u2"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u3"));
        }

        @Test
        @DisplayName("所有用戶都綁定 → skippedNotAssigned = 0")
        void allUsersBound_noSkipped() {
            setupAllUsersPassPreFilter(user1, user2);

            when(signalSourceService.resolveTargetUserIds("ch-all", "g-all"))
                    .thenReturn(Optional.of(Set.of("u1", "u2")));
            when(signalSourceService.resolveSourceId("ch-all", "g-all"))
                    .thenReturn(Optional.of(4L));

            TradeRequest request = createRequest("CLOSE", "ETHUSDT", null);
            request.setSource(SignalSource.builder()
                    .channelId("ch-all").guildId("g-all").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);
        }

        @Test
        @DisplayName("fallback 全量廣播 → skippedNotAssigned = 0（無匹配來源不算排除）")
        void fallbackBroadcast_skippedCountIsZero() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            when(signalSourceService.resolveTargetUserIds("ch-miss", "g-miss"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-miss", "g-miss"))
                    .thenReturn(Optional.empty());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-miss").guildId("g-miss").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(3);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);
        }
    }
}
