package com.trader.papertrade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.NotificationService;
import com.trader.papertrade.config.PaperTradingConfig;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.*;
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
 * 模擬交易隔離性測試
 *
 * 驗證 Paper Trading 不會影響真實交易流程：
 * 1. AUTO 模式不會建立模擬交易
 * 2. SHADOW + paperTradingEnabled=false 不會建立模擬交易
 * 3. SHADOW + paperTradingEnabled=true 才建立模擬交易
 * 4. 模擬交易使用 PAPER_TRADE_SYSTEM userId，不會混入真實用戶
 * 5. 模擬交易的 simulated=true 標記正確寫入
 * 6. 真實交易的 simulated 預設為 false
 */
class PaperTradeIsolationTest {

    // ==================== BroadcastTradeService 路由隔離 ====================

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
    private PaperTradeService paperTradeService;
    private BinancePriceClient binancePriceClient;

    private BroadcastTradeService service;

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
        paperTradeService = mock(PaperTradeService.class);
        binancePriceClient = mock(BinancePriceClient.class);

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
                paperTradeService,
                binancePriceClient,
                15,
                0L);

        // 預設 AI 評分 — 非同步，立即返回 null
        when(signalScoringService.scoreAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void tearDown() {
        broadcastExecutor.shutdownNow();
    }

    /**
     * 設定 SHADOW ASSIGNED 路由所需的用戶 mock（避免 activeUsers 為空而提前返回）
     */
    private void setupShadowUsers() {
        when(signalSourceService.resolveTargetUserIds(any(), any()))
                .thenReturn(Optional.of(Set.of("u1")));
        User user = User.builder().userId("u1").email("a@test.com").name("A")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u1"));
    }

    private TradeRequest createRequest(String action, String symbol, String side) {
        TradeRequest req = new TradeRequest();
        req.setAction(action);
        req.setSymbol(symbol);
        req.setSide(side);
        req.setEntryPrice(50000.0);
        req.setStopLoss(49000.0);
        req.setTakeProfit(52000.0);
        req.setSource(SignalSource.builder()
                .platform("DISCORD")
                .channelId("shadow-ch-1")
                .guildId("g1")
                .authorName("TestTeacher")
                .build());
        return req;
    }

    // ==================== AUTO 流程隔離 ====================

    @Test
    @DisplayName("AUTO 模式 ENTRY — 不應呼叫 paperTradeService")
    void autoMode_entry_noPaperTrade() {
        // 設定 AUTO 來源
        SignalSourceConfig autoSource = SignalSourceConfig.builder()
                .id(1L)
                .channelId("auto-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                .routingMode(SignalSourceConfig.RoutingMode.GLOBAL)
                .enabled(true)
                .paperTradingEnabled(false)
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(autoSource));

        // 設定用戶
        User user = User.builder().userId("u1").email("a@test.com").name("A")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u1"));
        when(binanceFuturesService.executeSignalForBroadcast(any(), anyString())).thenReturn(List.of());

        TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
        service.broadcastTrade(request);

        // AUTO 不應呼叫 paperTradeService
        verify(paperTradeService, never()).createPaperTrade(any(), any());
        verify(paperTradeService, never()).closePaperTrade(any(), any(), anyDouble(), any());
    }

    // ==================== SHADOW 流程隔離 ====================

    @Test
    @DisplayName("SHADOW + paperTradingEnabled=false — 不應建立模擬交易")
    void shadowMode_paperTradingDisabled_noPaperTrade() {
        setupShadowUsers();
        SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                .id(2L)
                .channelId("shadow-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                .enabled(true)
                .paperTradingEnabled(false) // 關閉
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(shadowSource));

        TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
        service.broadcastTrade(request);

        verify(paperTradeService, never()).createPaperTrade(any(), any());
    }

    @Test
    @DisplayName("SHADOW + paperTradingEnabled=true + ENTRY — 應建立模擬交易")
    void shadowMode_paperTradingEnabled_entry_createsPaperTrade() {
        setupShadowUsers();
        SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                .id(3L)
                .channelId("shadow-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                .enabled(true)
                .paperTradingEnabled(true) // 開啟
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(shadowSource));

        TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
        service.broadcastTrade(request);

        verify(paperTradeService).createPaperTrade(eq(request), any());
    }

    @Test
    @DisplayName("SHADOW + paperTradingEnabled=true + CLOSE — 應觸發模擬平倉")
    void shadowMode_paperTradingEnabled_close_closesPaperTrade() {
        setupShadowUsers();
        SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                .id(4L)
                .channelId("shadow-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                .enabled(true)
                .paperTradingEnabled(true)
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(shadowSource));
        when(binancePriceClient.getMarkPrice("BTCUSDT")).thenReturn(51000.0);

        TradeRequest request = createRequest("CLOSE", "BTCUSDT", null);
        service.broadcastTrade(request);

        verify(binancePriceClient).getMarkPrice("BTCUSDT");
        verify(paperTradeService).closePaperTrade(eq("BTCUSDT"), eq("shadow-ch-1"), eq(51000.0), eq("SIGNAL_CLOSE"));
    }

    @Test
    @DisplayName("SHADOW + paperTradingEnabled=true + MOVE_SL — 應更新模擬止損")
    void shadowMode_paperTradingEnabled_moveSl_updatesPaperTrade() {
        setupShadowUsers();
        SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                .id(5L)
                .channelId("shadow-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                .enabled(true)
                .paperTradingEnabled(true)
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(shadowSource));

        TradeRequest request = createRequest("MOVE_SL", "BTCUSDT", null);
        request.setNewStopLoss(50500.0);
        request.setNewTakeProfit(55000.0);
        service.broadcastTrade(request);

        verify(paperTradeService).movePaperStopLoss("BTCUSDT", "shadow-ch-1", 50500.0, 55000.0);
    }

    // ==================== 模擬交易 Entity 標記 ====================

    @Test
    @DisplayName("模擬交易 userId 固定為 PAPER_TRADE_SYSTEM — 不會混入真實用戶")
    void paperTrade_userId_isPaperTradeSystem() {
        TradeRepository realTradeRepo = mock(TradeRepository.class);
        BinancePriceClient priceClient = mock(BinancePriceClient.class);
        PaperTradingConfig config = new PaperTradingConfig(1000, 10, 90000, 0.10);
        PaperTradeService paperService = new PaperTradeService(realTradeRepo, config, new ObjectMapper(), priceClient);

        when(priceClient.getMarkPrice("BTCUSDT")).thenReturn(50500.0);

        when(realTradeRepo.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        TradeRequest request = new TradeRequest();
        request.setSymbol("BTCUSDT");
        request.setAction("ENTRY");
        request.setSide("LONG");
        request.setEntryPrice(50000.0);

        Trade result = paperService.createPaperTrade(request, null);

        assertThat(result.getUserId()).isEqualTo("PAPER_TRADE_SYSTEM");
        assertThat(result.isSimulated()).isTrue();
        assertThat(result.getEntryOrderId()).isEqualTo("PAPER");
    }

    @Test
    @DisplayName("真實交易 Trade.builder() 預設 simulated=false")
    void realTrade_defaultSimulatedFalse() {
        Trade realTrade = Trade.builder()
                .tradeId("real-1")
                .userId("real-user-1")
                .symbol("BTCUSDT")
                .side("LONG")
                .status("OPEN")
                .build();

        assertThat(realTrade.isSimulated()).isFalse();
    }

    // ==================== Paper Trade 失敗容錯 ====================

    @Test
    @DisplayName("SHADOW 模擬交易建立失敗 — 不影響主流程（BroadcastLog 仍記錄）")
    void shadowMode_paperTradeFailure_doesNotAffectMainFlow() {
        setupShadowUsers();
        SignalSourceConfig shadowSource = SignalSourceConfig.builder()
                .id(6L)
                .channelId("shadow-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.SHADOW)
                .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED)
                .enabled(true)
                .paperTradingEnabled(true)
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(shadowSource));
        // 模擬交易建立拋出異常
        when(paperTradeService.createPaperTrade(any(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");

        // 不應拋出異常
        assertThatCode(() -> service.broadcastTrade(request)).doesNotThrowAnyException();

        // BroadcastLog 仍然記錄
        verify(broadcastLogRepository).save(any(BroadcastLog.class));
    }

    @Test
    @DisplayName("AUTO + paperTradingEnabled=true — AUTO 模式下即使開啟也不建模擬單")
    void autoMode_withPaperTradingEnabled_stillNoPaperTrade() {
        // 即使 AUTO 來源誤設 paperTradingEnabled=true，也不應建模擬單
        SignalSourceConfig autoSource = SignalSourceConfig.builder()
                .id(7L)
                .channelId("auto-ch-1")
                .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                .routingMode(SignalSourceConfig.RoutingMode.GLOBAL)
                .enabled(true)
                .paperTradingEnabled(true) // 誤設
                .build();

        when(signalSourceService.resolveSource(any(), any()))
                .thenReturn(Optional.of(autoSource));

        User user = User.builder().userId("u1").email("a@test.com").name("A")
                .enabled(true).autoTradeEnabled(true).role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u1"));
        when(binanceFuturesService.executeSignalForBroadcast(any(), anyString())).thenReturn(List.of());

        TradeRequest request = createRequest("ENTRY", "BTCUSDT", "LONG");
        service.broadcastTrade(request);

        // AUTO 流程走真實交易，不應呼叫 paperTradeService
        verify(paperTradeService, never()).createPaperTrade(any(), any());
        // 但應該走真實交易流程
        verify(binanceFuturesService).executeSignalForBroadcast(any(), eq("u1"));
    }
}
