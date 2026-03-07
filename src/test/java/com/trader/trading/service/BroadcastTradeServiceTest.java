package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BroadcastTradeService 單元測試
 *
 * 覆蓋：
 * - saveBroadcastLog 正確記錄欄位
 * - save 失敗不影響主流程
 * - empty users 也記錄一筆 log
 */
class BroadcastTradeServiceTest {

    private UserRepository userRepository;
    private ExchangeAdapterFactory exchangeAdapterFactory;
    private TradingOrchestrator orchestrator;
    private TradeConfigResolver tradeConfigResolver;
    private TradeRecordService tradeRecordService;
    private SignalDeduplicationService deduplicationService;
    private SymbolLockRegistry symbolLockRegistry;
    private NotificationService discordWebhookService;
    private UserApiKeyService userApiKeyService;
    private SubscriptionRepository subscriptionRepository;
    private SignalScoringService signalScoringService;
    private TradeRepository tradeRepository;
    private BroadcastLogRepository broadcastLogRepository;
    private ObjectMapper objectMapper;
    private ExecutorService broadcastExecutor;
    private ExchangeAdapter mockAdapter;

    private BroadcastTradeService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        exchangeAdapterFactory = mock(ExchangeAdapterFactory.class);
        orchestrator = mock(TradingOrchestrator.class);
        tradeConfigResolver = mock(TradeConfigResolver.class);
        tradeRecordService = mock(TradeRecordService.class);
        deduplicationService = mock(SignalDeduplicationService.class);
        symbolLockRegistry = mock(SymbolLockRegistry.class);
        discordWebhookService = mock(NotificationService.class);
        userApiKeyService = mock(UserApiKeyService.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        signalScoringService = mock(SignalScoringService.class);
        tradeRepository = mock(TradeRepository.class);
        broadcastLogRepository = mock(BroadcastLogRepository.class);
        objectMapper = new ObjectMapper(); // real ObjectMapper for JSON serialization
        broadcastExecutor = Executors.newFixedThreadPool(2);
        mockAdapter = mock(ExchangeAdapter.class);

        service = new BroadcastTradeService(
                userRepository,
                exchangeAdapterFactory,
                orchestrator,
                tradeConfigResolver,
                tradeRecordService,
                deduplicationService,
                symbolLockRegistry,
                discordWebhookService,
                userApiKeyService,
                subscriptionRepository,
                signalScoringService,
                tradeRepository,
                broadcastLogRepository,
                objectMapper,
                broadcastExecutor);

        // 預設 AI 評分 — 非同步，立即返回 null
        when(signalScoringService.scoreAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 預設：exchangeAdapterFactory 回傳 mockAdapter
        when(exchangeAdapterFactory.getAdapter(anyString())).thenReturn(mockAdapter);

        // 預設：tradeConfigResolver 回傳允許 BTCUSDT 和 ETHUSDT 的 config
        when(tradeConfigResolver.resolve(anyString())).thenReturn(new EffectiveTradeConfig(
                1.0, 1000.0, 500.0, 0.05, 0.1, 3, 1.5, 10,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"));

        // 預設：getUserExchangeKeys 回傳有效 key
        when(userApiKeyService.getUserExchangeKeys(anyString(), anyString()))
                .thenReturn(Optional.of(new ExchangeKeys("key", "secret")));

        // 預設：symbolLockRegistry 回傳新的 lock
        when(symbolLockRegistry.getLock(anyString())).thenReturn(new ReentrantLock());
    }

    @AfterEach
    void tearDown() {
        broadcastExecutor.shutdownNow();
    }

    /** 建立一個成功的 OrderResult（orchestrator 回傳需至少一個 success + orderId） */
    private List<OrderResult> successResult() {
        return List.of(OrderResult.builder()
                .success(true).orderId("mock-order-1").symbol("BTCUSDT")
                .price(50000.0).quantity(0.01).build());
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

    // ── saveBroadcastLog 持久化 ──

    @Nested
    @DisplayName("廣播紀錄持久化")
    class SaveBroadcastLogTests {

        @Test
        @DisplayName("empty users → 仍儲存一筆 log（counts 全為 0）")
        void emptyUsersSavesLog() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            service.broadcastTrade(request);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getSignalAction()).isEqualTo("ENTRY");
            assertThat(saved.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(saved.getSide()).isEqualTo("LONG");
            assertThat(saved.getTotalUsers()).isEqualTo(0);
            assertThat(saved.getSuccessCount()).isEqualTo(0);
            assertThat(saved.getFailCount()).isEqualTo(0);
            assertThat(saved.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("有用戶成功執行 → log 記錄正確的 success/fail counts")
        void successfulBroadcastSavesCorrectCounts() throws Exception {
            User admin = User.builder().userId("admin1").email("admin@test.com")
                    .enabled(true).autoTradeEnabled(false).role(User.Role.ADMIN).build();
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(admin, user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());

            TradeRequest request = createRequest("ENTRY", "ETHUSDT", "SHORT");
            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("successCount")).isEqualTo(1);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getTotalUsers()).isEqualTo(1);
            assertThat(saved.getSuccessCount()).isEqualTo(1);
            assertThat(saved.getFailCount()).isEqualTo(0);
            assertThat(saved.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(saved.getSide()).isEqualTo("SHORT");
            assertThat(saved.getDurationMs()).isNotNull();
            assertThat(saved.getDurationMs()).isGreaterThanOrEqualTo(0);
            // userResults JSON should contain u1
            assertThat(saved.getUserResults()).contains("u1");
            assertThat(saved.getUserResults()).contains("a@test.com");
        }

        @Test
        @DisplayName("用戶執行失敗 → log 記錄 failCount + errorMessage")
        void failedBroadcastRecordsError() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any()))
                    .thenThrow(new RuntimeException("Insufficient balance"));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("failCount")).isEqualTo(1);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getFailCount()).isEqualTo(1);
            assertThat(saved.getSuccessCount()).isEqualTo(0);
            assertThat(saved.getUserResults()).contains("Insufficient balance");
        }

        @Test
        @DisplayName("save 失敗 → 不影響交易主流程，仍返回 COMPLETED")
        void saveFailureDoesNotAffectMainFlow() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());
            when(broadcastLogRepository.save(any()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            Map<String, Object> result = service.broadcastTrade(request);

            // 主流程不受影響
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("successCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("signal 欄位正確映射 — entryPrice / stopLoss / takeProfit / isDca / sourceAuthor")
        void signalFieldsCorrectlyMapped() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setIsDca(true);
            request.setSource(SignalSource.builder().authorName("陳哥").platform("DISCORD").build());

            service.broadcastTrade(request);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getEntryPrice()).isEqualTo(50000.0);
            assertThat(saved.getStopLoss()).isEqualTo(49000.0);
            assertThat(saved.getTakeProfit()).isEqualTo(52000.0);
            assertThat(saved.getIsDca()).isTrue();
            assertThat(saved.getSourceAuthor()).isEqualTo("陳哥");
        }

        @Test
        @DisplayName("source 為 null → sourceAuthor 為 null")
        void nullSourceAuthor() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of());

            TradeRequest request = createRequest("CLOSE", "ETHUSDT", null);
            request.setSource(null);

            service.broadcastTrade(request);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getSourceAuthor()).isNull();
        }

        @Test
        @DisplayName("AI 評分可用 → log 記錄 aiConfidence + aiReasoning")
        void aiScoreRecorded() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());

            SignalScore score = SignalScore.builder()
                    .confidence(85).riskLevel("LOW").reasoning("Strong trend").latencyMs(500).build();
            when(signalScoringService.scoreAsync(any()))
                    .thenReturn(CompletableFuture.completedFuture(score));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            service.broadcastTrade(request);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getAiConfidence()).isEqualTo(85);
            assertThat(saved.getAiReasoning()).isEqualTo("Strong trend");
        }

        @Test
        @DisplayName("指定用戶模式 → skippedNotTargeted 記錄在 log 中")
        void targetedBroadcastSkippedNotTargetedInLog() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1", "u2"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(
                    Map.of("u1", Set.of("BINANCE"), "u2", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setTargetUserIds(List.of("u1"));

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("skippedNotTargeted")).isEqualTo(1); // u2

            // orchestrator.executeSignal 被呼叫（代表 u1 執行了）
            verify(orchestrator).executeSignal(any(), any());
        }

        @Test
        @DisplayName("skipped counts 正確記錄 — 無訂閱 + 無 API Key")
        void skippedCountsRecorded() {
            User user1 = User.builder().userId("u1").email("a@test.com")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
            User user2 = User.builder().userId("u2").email("b@test.com")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
            User user3 = User.builder().userId("u3").email("c@test.com")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3));
            // u1 有訂閱, u2 有訂閱, u3 無訂閱
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1", "u2"));
            // u1 有 API Key, u2 無 API Key
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            // u1 will execute, but let's make it succeed
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());

            service.broadcastTrade(request);

            ArgumentCaptor<BroadcastLog> captor = ArgumentCaptor.forClass(BroadcastLog.class);
            verify(broadcastLogRepository).save(captor.capture());

            BroadcastLog saved = captor.getValue();
            assertThat(saved.getSkippedNoSub()).isEqualTo(1);  // u3
            assertThat(saved.getSkippedNoKey()).isEqualTo(1);  // u2
            assertThat(saved.getTotalUsers()).isEqualTo(1);     // u1
        }
    }

    // ── 指定用戶模式 ──

    @Nested
    @DisplayName("指定用戶廣播 (targetUserIds)")
    class TargetedBroadcast {

        @Test
        @DisplayName("targetUserIds = null → 原有行為，全員廣播")
        void nullTargetUserIdsBroadcastsToAll() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1", "u2"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(
                    Map.of("u1", Set.of("BINANCE"), "u2", Set.of("BINANCE")));
            when(orchestrator.executeClose(any(), any())).thenReturn(successResult());

            TradeRequest request = createRequest("CLOSE", "BTCUSDT", null);
            request.setTargetUserIds(null); // 明確設 null

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(2);
            assertThat(result.get("successCount")).isEqualTo(2);
            assertThat(result.get("skippedNotTargeted")).isEqualTo(0);
            verify(orchestrator, times(2)).executeClose(any(), any());
        }

        @Test
        @DisplayName("targetUserIds = [u1] → 只對 u1 執行，u2 被排除")
        void targetSingleUser() throws Exception {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1, user2));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1", "u2"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(
                    Map.of("u1", Set.of("BINANCE"), "u2", Set.of("BINANCE")));
            when(orchestrator.executeSignal(any(), any())).thenReturn(successResult());

            TradeRequest request = createRequest("ENTRY", "ETHUSDT", "LONG");
            request.setTargetUserIds(List.of("u1"));

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("skippedNotTargeted")).isEqualTo(1);
            // orchestrator 只被呼叫一次（u1）
            verify(orchestrator, times(1)).executeSignal(any(), any());
        }

        @Test
        @DisplayName("targetUserIds = [不存在的ID] → 所有用戶被排除，走 empty users 邏輯")
        void targetNonExistentUser() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of("u1", Set.of("BINANCE")));

            TradeRequest request = createRequest("CLOSE", "BTCUSDT", null);
            request.setTargetUserIds(List.of("non-existent-id"));

            Map<String, Object> result = service.broadcastTrade(request);

            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNotTargeted")).isEqualTo(1); // u1 被排除
            verify(orchestrator, never()).executeClose(any(), any());
            verify(orchestrator, never()).executeSignal(any(), any());
        }

        @Test
        @DisplayName("targetUserIds 中的用戶無 API Key → 仍被 skip，不繞過安全檢查")
        void targetUserWithoutApiKeyStillSkipped() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();

            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userApiKeyService.getUserExchangeMap()).thenReturn(Map.of()); // u1 無 API Key

            TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
            request.setTargetUserIds(List.of("u1")); // 指定 u1，但 u1 無 API Key

            Map<String, Object> result = service.broadcastTrade(request);

            // u1 在 API Key 篩選階段就被排除了，targetUserIds 過濾不會碰到他
            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNoApiKey")).isEqualTo(1);
            assertThat(result.get("skippedNotTargeted")).isEqualTo(0); // targetUserIds 過濾時 activeUsers 已為空
            verify(orchestrator, never()).executeSignal(any(), any());
            verify(orchestrator, never()).executeClose(any(), any());
        }
    }
}
