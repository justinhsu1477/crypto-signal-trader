package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.RiskLevel;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.service.SignalSourceService;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.papertrade.service.BinancePriceClient;
import com.trader.papertrade.service.PaperTradeService;
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
                mock(PaperTradeService.class),
                mock(BinancePriceClient.class),
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

    private SignalSourceConfig buildSourceConfig(Long id) {
        return SignalSourceConfig.builder()
                .id(id).name("test-source").tradeMode(SignalSourceConfig.TradeMode.AUTO)
                .riskMultiplier(1.0).build();
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
            when(signalSourceService.resolveSource("ch-123", "g-456"))
                    .thenReturn(Optional.of(buildSourceConfig(1L)));

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
            when(signalSourceService.resolveSource("unknown-ch", "unknown-g"))
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
            when(signalSourceService.resolveSource("ch-empty", "g-empty"))
                    .thenReturn(Optional.of(buildSourceConfig(2L)));

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
        @DisplayName("GLOBAL 來源 + 無人綁定 ASSIGNED → 全量廣播給所有人")
        void globalSource_noBoundUsers_broadcastsToAll() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            when(signalSourceService.resolveTargetUserIds("ch-global", "g-global"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-global", "g-global"))
                    .thenReturn(Optional.of(10L));
            when(signalSourceService.resolveSource("ch-global", "g-global"))
                    .thenReturn(Optional.of(buildSourceConfig(10L)));
            // 無人綁定 ASSIGNED 來源
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

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

        @Test
        @DisplayName("GLOBAL 來源 + u2 已綁定 ASSIGNED → 排除 u2，只廣播給 u1, u3")
        void globalSource_excludesBoundUsers() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            when(signalSourceService.resolveTargetUserIds("ch-global", "g-global"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-global", "g-global"))
                    .thenReturn(Optional.of(10L));
            when(signalSourceService.resolveSource("ch-global", "g-global"))
                    .thenReturn(Optional.of(buildSourceConfig(10L)));
            // u2 已綁定到某個 ASSIGNED 來源
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of("u2"));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-global").guildId("g-global").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2); // u1, u3
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(1); // u2 被排除

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), eq("u2"));
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u3"));
        }

        @Test
        @DisplayName("GLOBAL 來源 + 所有人都綁定 ASSIGNED → 無人收到 GLOBAL")
        void globalSource_allBound_noOneReceives() {
            setupAllUsersPassPreFilter(user1, user2, user3);

            when(signalSourceService.resolveTargetUserIds("ch-global", "g-global"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-global", "g-global"))
                    .thenReturn(Optional.of(10L));
            when(signalSourceService.resolveSource("ch-global", "g-global"))
                    .thenReturn(Optional.of(buildSourceConfig(10L)));
            // 所有人都綁定了 ASSIGNED 來源
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of("u1", "u2", "u3"));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-global").guildId("g-global").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(3);

            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), anyString());
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

    // ======================== TradeMode 控制 ========================

    @Nested
    @DisplayName("TradeMode 控制 (SHADOW / MANUAL)")
    class TradeModeTests {

        @Test
        @DisplayName("SHADOW 模式 → 記錄但不執行 Binance 交易")
        void shadowMode_recordsButDoesNotExecute() {
            setupAllUsersPassPreFilter(user1, user2);

            SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                    .id(20L).name("shadow-src")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(shadowSource));
            when(signalSourceService.resolveTargetUserIds("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(20L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-shadow").guildId("g-shadow").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("SHADOW_RECORDED");
            assertThat(result.get("tradeMode")).isEqualTo("SHADOW");
            assertThat(result.get("sourceId")).isEqualTo(20L);
            assertThat(result.get("totalEligibleUsers")).isEqualTo(2);

            // Binance 不應被呼叫
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), anyString());
        }

        @Test
        @DisplayName("SHADOW 模式 → AI 評分結果存入 BroadcastLog")
        void shadowMode_savesAiScoreToBroadcastLog() {
            setupAllUsersPassPreFilter(user1);

            SignalScore score = SignalScore.builder()
                    .confidence(72).riskLevel(RiskLevel.MEDIUM).reasoning("R:R 尚可").latencyMs(1500L).build();
            when(signalScoringService.scoreAsync(any()))
                    .thenReturn(CompletableFuture.completedFuture(score));

            SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                    .id(20L).name("shadow-src")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(shadowSource));
            when(signalSourceService.resolveTargetUserIds("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(20L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-shadow").guildId("g-shadow").platform("DISCORD").build());

            service.broadcastTrade(request);

            // 驗證 BroadcastLog 包含 AI 評分
            var captor = org.mockito.ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());
            BroadcastLog savedLog = captor.getValue();
            assertThat(savedLog.getAiConfidence()).isEqualTo(72);
            assertThat(savedLog.getAiReasoning()).isEqualTo("R:R 尚可");
            assertThat(savedLog.getStatus()).isEqualTo("SHADOW_RECORDED");
        }

        @Test
        @DisplayName("MANUAL 模式 → 跳過廣播，僅記錄")
        void manualMode_skipsBroadcast() {
            setupAllUsersPassPreFilter(user1, user2);

            SignalSourceConfig manualSource = SignalSourceConfig.builder()
                    .id(21L).name("manual-src")
                    .tradeMode(SignalSourceConfig.TradeMode.MANUAL)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-manual", "g-manual"))
                    .thenReturn(Optional.of(manualSource));
            when(signalSourceService.resolveTargetUserIds("ch-manual", "g-manual"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-manual", "g-manual"))
                    .thenReturn(Optional.of(21L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "ETHUSDT", "SHORT");
            request.setSource(SignalSource.builder()
                    .channelId("ch-manual").guildId("g-manual").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("MANUAL_SKIPPED");
            assertThat(result.get("tradeMode")).isEqualTo("MANUAL");
            assertThat(result.get("sourceId")).isEqualTo(21L);

            // Binance 不應被呼叫（MANUAL 模式跳過廣播）
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), anyString());
        }

        @Test
        @DisplayName("AUTO 模式 → 正常執行交易（既有行為）")
        void autoMode_executesNormally() {
            setupAllUsersPassPreFilter(user1);

            SignalSourceConfig autoSource = SignalSourceConfig.builder()
                    .id(22L).name("auto-src")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-auto", "g-auto"))
                    .thenReturn(Optional.of(autoSource));
            when(signalSourceService.resolveTargetUserIds("ch-auto", "g-auto"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-auto", "g-auto"))
                    .thenReturn(Optional.of(22L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-auto").guildId("g-auto").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(1);

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
        }

        @Test
        @DisplayName("無 resolvedSource → tradeMode 為 null → 正常執行（向下相容）")
        void noResolvedSource_executesNormally() {
            setupAllUsersPassPreFilter(user1);

            when(signalSourceService.resolveSource("ch-unknown", "g-unknown"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveTargetUserIds("ch-unknown", "g-unknown"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-unknown", "g-unknown"))
                    .thenReturn(Optional.empty());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-unknown").guildId("g-unknown").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(1);

            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
        }

        @Test
        @DisplayName("SHADOW 模式 → 不發 Admin 即時通知（靠每日報表）")
        void shadowMode_noAdminNotification() {
            User admin = User.builder().userId("admin-1").email("admin@test.com").name("Admin")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.ADMIN).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, admin));
            when(subscriptionRepository.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(new HashSet<>(List.of("u1")));

            SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                    .id(20L).name("shadow-src")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(shadowSource));
            when(signalSourceService.resolveTargetUserIds("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-shadow", "g-shadow"))
                    .thenReturn(Optional.of(20L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-shadow").guildId("g-shadow").platform("DISCORD").build());

            service.broadcastTrade(request);

            // SHADOW 模式不應發送 Admin 即時通知
            verify(discordWebhookService, never()).sendNotificationToUser(
                    eq("admin-1"), anyString(), anyString(), anyInt());
        }
    }

    // ======================== enabled 開關控制 ========================

    @Nested
    @DisplayName("來源 enabled 開關")
    class SourceEnabledTests {

        @Test
        @DisplayName("來源 enabled=false → 跳過廣播，回傳 SOURCE_DISABLED")
        void disabledSource_skipsBroadcast() {
            setupAllUsersPassPreFilter(user1, user2);

            SignalSourceConfig disabledSource = SignalSourceConfig.builder()
                    .id(40L).name("disabled-src")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                    .enabled(false)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-disabled", "g-disabled"))
                    .thenReturn(Optional.of(disabledSource));
            when(signalSourceService.resolveTargetUserIds("ch-disabled", "g-disabled"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-disabled", "g-disabled"))
                    .thenReturn(Optional.of(40L));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-disabled").guildId("g-disabled").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("SOURCE_DISABLED");
            assertThat(result.get("sourceId")).isEqualTo(40L);

            // 不應執行任何交易
            verify(binanceFuturesService, never()).executeSignalForBroadcast(any(), anyString());
        }

        @Test
        @DisplayName("來源 enabled=true → 正常廣播")
        void enabledSource_broadcastsNormally() {
            setupAllUsersPassPreFilter(user1);

            SignalSourceConfig enabledSource = SignalSourceConfig.builder()
                    .id(41L).name("enabled-src")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                    .enabled(true)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-enabled", "g-enabled"))
                    .thenReturn(Optional.of(enabledSource));
            when(signalSourceService.resolveTargetUserIds("ch-enabled", "g-enabled"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-enabled", "g-enabled"))
                    .thenReturn(Optional.of(41L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-enabled").guildId("g-enabled").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
        }

        @Test
        @DisplayName("無匹配來源（resolvedSource=null）→ 不受 enabled 影響，正常廣播")
        void unknownSource_notAffectedByEnabled() {
            setupAllUsersPassPreFilter(user1);

            when(signalSourceService.resolveSource("ch-new", "g-new"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveTargetUserIds("ch-new", "g-new"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-new", "g-new"))
                    .thenReturn(Optional.empty());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-new").guildId("g-new").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
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
            when(signalSourceService.resolveSource("ch-one", "g-one"))
                    .thenReturn(Optional.of(buildSourceConfig(3L)));

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
            when(signalSourceService.resolveSource("ch-all", "g-all"))
                    .thenReturn(Optional.of(buildSourceConfig(4L)));

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
            when(signalSourceService.resolveSource("ch-miss", "g-miss"))
                    .thenReturn(Optional.empty());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-miss").guildId("g-miss").platform("DISCORD").build());

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(3);
            assertThat(result.get("skippedNotAssigned")).isEqualTo(0);
        }
    }

    // ======================== Admin 通知過濾（GLOBAL vs ASSIGNED） ========================

    @Nested
    @DisplayName("Admin 通知過濾 (isGlobalBroadcast)")
    class AdminNotificationFilterTests {

        private User admin;

        @BeforeEach
        void setUpAdmin() {
            admin = User.builder().userId("admin-1").email("admin@test.com").name("Admin")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.ADMIN).build();
        }

        @Test
        @DisplayName("ASSIGNED 來源 → 不發 Admin 通知（避免訊息過多）")
        void assignedSource_noAdminNotification() {
            setupAllUsersPassPreFilter(user1);

            SignalSourceConfig assignedSource = SignalSourceConfig.builder()
                    .id(30L).name("assigned-src")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-assigned", "g-assigned"))
                    .thenReturn(Optional.of(assignedSource));
            when(signalSourceService.resolveTargetUserIds("ch-assigned", "g-assigned"))
                    .thenReturn(Optional.of(Set.of("u1")));
            when(signalSourceService.resolveSourceId("ch-assigned", "g-assigned"))
                    .thenReturn(Optional.of(30L));
            when(userRepository.findAll()).thenReturn(List.of(user1, admin));
            when(subscriptionRepository.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(new HashSet<>(List.of("u1")));
            when(binanceFuturesService.executeSignalForBroadcast(any(), anyString()))
                    .thenReturn(List.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-assigned").guildId("g-assigned").platform("DISCORD").build());

            service.broadcastTrade(request);

            // ASSIGNED 來源 → 不應對 Admin 發送 per-user 通知
            verify(discordWebhookService, never()).sendNotificationToUser(
                    eq("admin-1"), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("GLOBAL 來源 → 發送 Admin 通知")
        void globalSource_sendsAdminNotification() {
            setupAllUsersPassPreFilter(user1);

            SignalSourceConfig globalSource = SignalSourceConfig.builder()
                    .id(31L).name("global-src")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                    .routingMode(SignalSourceConfig.RoutingMode.GLOBAL)
                    .riskMultiplier(1.0).build();

            when(signalSourceService.resolveSource("ch-global2", "g-global2"))
                    .thenReturn(Optional.of(globalSource));
            when(signalSourceService.resolveTargetUserIds("ch-global2", "g-global2"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-global2", "g-global2"))
                    .thenReturn(Optional.of(31L));
            when(signalSourceService.getUserIdsBoundToAssignedSources())
                    .thenReturn(Set.of());
            when(userRepository.findAll()).thenReturn(List.of(user1, admin));
            when(subscriptionRepository.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(new HashSet<>(List.of("u1")));
            when(binanceFuturesService.executeSignalForBroadcast(any(), anyString()))
                    .thenReturn(List.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-global2").guildId("g-global2").platform("DISCORD").build());

            service.broadcastTrade(request);

            // GLOBAL 來源 → 應對 Admin 發送 per-user 通知（至少一次：訊號詳情 或 彙總報告）
            verify(discordWebhookService, atLeastOnce()).sendNotificationToUser(
                    eq("admin-1"), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("resolvedSource 為 null → 視為 GLOBAL，發送 Admin 通知")
        void nullSource_treatedAsGlobal_sendsAdminNotification() {
            setupAllUsersPassPreFilter(user1);

            when(signalSourceService.resolveSource("ch-unknown2", "g-unknown2"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveTargetUserIds("ch-unknown2", "g-unknown2"))
                    .thenReturn(Optional.empty());
            when(signalSourceService.resolveSourceId("ch-unknown2", "g-unknown2"))
                    .thenReturn(Optional.empty());
            when(userRepository.findAll()).thenReturn(List.of(user1, admin));
            when(subscriptionRepository.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(new HashSet<>(List.of("u1")));
            when(binanceFuturesService.executeSignalForBroadcast(any(), anyString()))
                    .thenReturn(List.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setSource(SignalSource.builder()
                    .channelId("ch-unknown2").guildId("g-unknown2").platform("DISCORD").build());

            service.broadcastTrade(request);

            // null source → isGlobalBroadcast=true → 應對 Admin 發送通知
            verify(discordWebhookService, atLeastOnce()).sendNotificationToUser(
                    eq("admin-1"), anyString(), anyString(), anyInt());
        }
    }
}
