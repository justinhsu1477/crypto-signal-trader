package com.trader.trading.service;

import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnprotectedPositionRecoveryTaskTest {

    private TradeRepository tradeRepository;
    private ExchangeAdapterFactory exchangeAdapterFactory;
    private ExchangeAdapter defaultAdapter;
    private TradeRecordService tradeRecordService;
    private NotificationService notificationService;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private UserRepository userRepository;
    private SymbolLockRegistry symbolLockRegistry;
    private UnprotectedPositionRecoveryTask task;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        exchangeAdapterFactory = mock(ExchangeAdapterFactory.class);
        defaultAdapter = mock(ExchangeAdapter.class);
        when(exchangeAdapterFactory.getDefaultAdapter()).thenReturn(defaultAdapter);
        when(exchangeAdapterFactory.getAdapter(anyString())).thenReturn(defaultAdapter);
        tradeRecordService = mock(TradeRecordService.class);
        notificationService = mock(NotificationService.class);
        multiUserConfig = new MultiUserConfig();
        userApiKeyService = mock(UserApiKeyService.class);
        userRepository = mock(UserRepository.class);
        symbolLockRegistry = mock(SymbolLockRegistry.class);

        // 預設 symbolLockRegistry 回傳真實 ReentrantLock
        when(symbolLockRegistry.getLock(anyString())).thenReturn(new ReentrantLock());

        task = new UnprotectedPositionRecoveryTask(
                tradeRepository, exchangeAdapterFactory, tradeRecordService,
                notificationService, multiUserConfig, userApiKeyService,
                userRepository, symbolLockRegistry);
    }

    @AfterEach
    void tearDown() {
        TradeRecordService.clearCurrentUserId();
    }

    // ==================== 基本場景 ====================

    @Nested
    @DisplayName("基本場景")
    class BasicScenarios {

        @Test
        @DisplayName("無 OPEN Trade → 不呼叫 Binance")
        void noOpenTrades_doesNothing() {
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).getAllPositionAmounts();
            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("所有 Trade 有 SL 保護 → 不補掛")
        void allTradesProtected_noRecovery() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{65000.0, 0});

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("DB 有 OPEN 但 Binance 無持倉 → skip")
        void noPosition_skipsRecovery() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of()); // 無持倉

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).getCurrentSLTPPrices(any());
            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("有持倉無 SL → 補掛成功 → 通知 + recordEvent")
        void positionExistsNoSL_recoversSuccessfully() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss("BTCUSDT", "SELL", 65000.0, 0.001))
                    .thenReturn(OrderResult.builder().success(true).symbol("BTCUSDT").build());

            task.scheduledRecoveryCheck();

            verify(defaultAdapter).setStopLoss("BTCUSDT", "SELL", 65000.0, 0.001);
            verify(tradeRecordService).recordOrderEvent(eq("BTCUSDT"), eq("SL_RECOVERY"), any(), isNull());
            verify(notificationService).sendNotification(
                    eq("🔧 SL 自動恢復成功"), contains("BTCUSDT"), eq(NotificationService.COLOR_BLUE));
        }

        @Test
        @DisplayName("有持倉無 SL → 補掛失敗 → counter++ → WARN 通知")
        void positionExistsNoSL_recoveryFails() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss(any(), any(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("API timeout"));

            task.scheduledRecoveryCheck();

            assertThat(task.getRecoveryAttempts().get("t1")).isEqualTo(1);
        }

        @Test
        @DisplayName("Trade.stopLoss 為 null → 通知用戶手動設定")
        void stopLossNull_skipsWithNotification() {
            Trade trade = createTradeWithUser("t1", "BTCUSDT", "LONG", null, "user-a");
            multiUserConfig.setEnabled(true);
            when(userRepository.findAll()).thenReturn(List.of(createUser("user-a")));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key", "secret"))));
            when(tradeRepository.findByUserIdAndStatus("user-a", "OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
            verify(notificationService).sendNotificationToUser(eq("user-a"),
                    eq("⚠️ 持倉缺少止損保護"), contains("無法自動恢復"), eq(NotificationService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("LONG → closeSide=SELL")
        void sideMapping_longToSell() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss(any(), any(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.builder().success(true).build());

            task.scheduledRecoveryCheck();

            verify(defaultAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(65000.0), eq(0.001));
        }

        @Test
        @DisplayName("SHORT → closeSide=BUY")
        void sideMapping_shortToBuy() {
            Trade trade = createTrade("t1", "ETHUSDT", "SHORT", 3000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("ETHUSDT", -0.5));
            when(defaultAdapter.getCurrentSLTPPrices("ETHUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("ETHUSDT")).thenReturn(-0.5);
            when(defaultAdapter.setStopLoss(any(), any(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.builder().success(true).build());

            task.scheduledRecoveryCheck();

            verify(defaultAdapter).setStopLoss(eq("ETHUSDT"), eq("BUY"), eq(3000.0), eq(0.5));
        }
    }

    // ==================== Retry 限制 ====================

    @Nested
    @DisplayName("Retry 限制")
    class RetryLimiting {

        @Test
        @DisplayName("已達 MAX_RECOVERY_ATTEMPTS → 不再嘗試 → CRITICAL admin 通知")
        void maxAttemptsReached_stopsRetrying() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            task.getRecoveryAttempts().put("t1", UnprotectedPositionRecoveryTask.MAX_RECOVERY_ATTEMPTS);

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
            verify(notificationService).sendNotificationToAdmins(
                    eq("🚨 SL 恢復失敗（已達上限）"), contains("BTCUSDT"), eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("恢復成功 → counter 歸零")
        void successfulRecovery_resetsCounter() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            task.getRecoveryAttempts().put("t1", 2);

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss(any(), any(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.builder().success(true).build());

            task.scheduledRecoveryCheck();

            assertThat(task.getRecoveryAttempts()).doesNotContainKey("t1");
        }

        @Test
        @DisplayName("恢復失敗 → counter 從 0 → 1")
        void failureIncrementsCounter() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss(any(), any(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("timeout"));

            task.scheduledRecoveryCheck();

            assertThat(task.getRecoveryAttempts().get("t1")).isEqualTo(1);
        }
    }

    // ==================== 並行安全 ====================

    @Nested
    @DisplayName("並行安全")
    class ConcurrencySafety {

        @Test
        @DisplayName("tryLock 失敗 → skip，無 error")
        void lockBusy_skipsGracefully() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});

            // 模擬鎖已被占用
            ReentrantLock busyLock = new ReentrantLock();
            busyLock.lock(); // 持有鎖，tryLock 會失敗
            when(symbolLockRegistry.getLock("BTCUSDT")).thenReturn(busyLock);

            // 在另一個線程執行（因為 tryLock 同線程可重入）
            Thread thread = new Thread(() -> task.scheduledRecoveryCheck());
            thread.start();
            try { thread.join(5000); } catch (InterruptedException ignored) {}

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
            busyLock.unlock();
        }

        @Test
        @DisplayName("lock 外無 SL，lock 內有 SL → skip")
        void doubleCheckInsideLock_slAppeared() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            // 第一次（lock 外）：無 SL
            // 第二次（lock 內 double-check via getCurrentSLTPPrices）：有 SL
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT"))
                    .thenReturn(new double[]{0, 0})
                    .thenReturn(new double[]{65000.0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("lock 外有持倉，lock 內無持倉 → skip")
        void doubleCheckInsideLock_positionGone() {
            Trade trade = createTrade("t1", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            // lock 內 double-check：持倉已歸零
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);

            task.scheduledRecoveryCheck();

            verify(defaultAdapter, never()).setStopLoss(any(), any(), anyDouble(), anyDouble());
        }
    }

    // ==================== 多用戶模式 ====================

    @Nested
    @DisplayName("多用戶模式")
    class MultiUserMode {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("遍歷用戶並設定 per-user API Key")
        void multiUser_loopsWithPerUserKeys() {
            User userA = createUser("user-a");
            User userB = createUser("user-b");
            when(userRepository.findAll()).thenReturn(List.of(userA, userB));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-b"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-b", "secret-b"))));
            when(tradeRepository.findByUserIdAndStatus(anyString(), eq("OPEN"))).thenReturn(List.of());

            task.scheduledRecoveryCheck();

            verify(userApiKeyService).getUserPrimaryExchangeKeys("user-a");
            verify(userApiKeyService).getUserPrimaryExchangeKeys("user-b");
        }

        @Test
        @DisplayName("用戶無 API Key → skip")
        void multiUser_userWithoutApiKey_skipped() {
            User user = createUser("user-a");
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a")).thenReturn(Optional.empty());

            task.scheduledRecoveryCheck();

            verify(tradeRepository, never()).findByUserIdAndStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("User A 異常不影響 User B")
        void multiUser_oneUserFails_otherContinues() {
            User userA = createUser("user-a");
            User userB = createUser("user-b");
            when(userRepository.findAll()).thenReturn(List.of(userA, userB));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-b"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-b", "secret-b"))));

            // User A 查持倉時拋異常
            when(tradeRepository.findByUserIdAndStatus("user-a", "OPEN"))
                    .thenReturn(List.of(createTradeWithUser("t1", "BTCUSDT", "LONG", 65000.0, "user-a")));
            when(tradeRepository.findByUserIdAndStatus("user-b", "OPEN")).thenReturn(List.of());

            // 第一次呼叫（user-a）拋異常，第二次（user-b）正常
            when(defaultAdapter.getAllPositionAmounts())
                    .thenThrow(new RuntimeException("Exchange API error"))
                    .thenReturn(Map.of());

            task.scheduledRecoveryCheck();

            // User B 仍然被處理
            verify(tradeRepository).findByUserIdAndStatus("user-b", "OPEN");
        }

        @Test
        @DisplayName("有恢復時發 admin 彙總")
        void multiUser_adminSummary_sentWhenRecoveryHappens() {
            User user = createUser("user-a");
            Trade trade = createTradeWithUser("t1", "BTCUSDT", "LONG", 65000.0, "user-a");
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));
            when(tradeRepository.findByUserIdAndStatus("user-a", "OPEN")).thenReturn(List.of(trade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of("BTCUSDT", 0.001));
            when(defaultAdapter.getCurrentSLTPPrices("BTCUSDT")).thenReturn(new double[]{0, 0});
            when(defaultAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.001);
            when(defaultAdapter.setStopLoss("BTCUSDT", "SELL", 65000.0, 0.001))
                    .thenReturn(OrderResult.builder().success(true).symbol("BTCUSDT").build());

            task.scheduledRecoveryCheck();

            verify(notificationService).sendNotificationToAdmins(
                    eq("🔧 SL 保護檢查報告"), contains("恢復成功: 1"), eq(NotificationService.COLOR_BLUE));
        }
    }

    // ==================== Counter 清理 ====================

    @Nested
    @DisplayName("Counter 清理")
    class StaleCounterCleanup {

        @Test
        @DisplayName("已關閉 Trade 的 counter 被清理")
        void staleCountersRemoved() {
            task.getRecoveryAttempts().put("closed-trade", 2);
            task.getRecoveryAttempts().put("open-trade", 1);

            Trade openTrade = createTrade("open-trade", "BTCUSDT", "LONG", 65000.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(openTrade));
            when(defaultAdapter.getAllPositionAmounts()).thenReturn(Map.of());

            task.scheduledRecoveryCheck();

            assertThat(task.getRecoveryAttempts()).containsKey("open-trade");
            assertThat(task.getRecoveryAttempts()).doesNotContainKey("closed-trade");
        }
    }

    // ==================== 排程韌性 ====================

    @Nested
    @DisplayName("排程韌性")
    class SchedulerResilience {

        @Test
        @DisplayName("異常不外拋")
        void schedulerException_doesNotPropagate() {
            when(tradeRepository.findByStatus("OPEN")).thenThrow(new RuntimeException("DB down"));

            assertThatCode(() -> task.scheduledRecoveryCheck()).doesNotThrowAnyException();
        }
    }

    // ==================== Helper ====================

    private Trade createTrade(String tradeId, String symbol, String side, Double stopLoss) {
        return Trade.builder()
                .tradeId(tradeId)
                .symbol(symbol)
                .side(side)
                .stopLoss(stopLoss)
                .status("OPEN")
                .build();
    }

    private Trade createTradeWithUser(String tradeId, String symbol, String side, Double stopLoss, String userId) {
        return Trade.builder()
                .tradeId(tradeId)
                .symbol(symbol)
                .side(side)
                .stopLoss(stopLoss)
                .status("OPEN")
                .userId(userId)
                .build();
    }

    private User createUser(String userId) {
        return User.builder()
                .userId(userId)
                .enabled(true)
                .build();
    }
}
