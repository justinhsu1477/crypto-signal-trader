package com.trader.trading.service;

import com.google.gson.Gson;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiquidationDetectionTaskTest {

    private BinanceFuturesService binanceFuturesService;
    private TradeRecordService tradeRecordService;
    private TradeRepository tradeRepository;
    private NotificationService notificationService;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private UserRepository userRepository;
    private LiquidationDetectionTask task;

    @BeforeEach
    void setUp() {
        binanceFuturesService = mock(BinanceFuturesService.class);
        tradeRecordService = mock(TradeRecordService.class);
        tradeRepository = mock(TradeRepository.class);
        notificationService = mock(NotificationService.class);
        multiUserConfig = new MultiUserConfig();
        userApiKeyService = mock(UserApiKeyService.class);
        userRepository = mock(UserRepository.class);

        task = new LiquidationDetectionTask(
                binanceFuturesService, tradeRecordService, tradeRepository,
                notificationService, multiUserConfig, userApiKeyService,
                userRepository, new Gson());
    }

    @AfterEach
    void tearDown() {
        BinanceFuturesService.clearCurrentUserKeys();
        TradeRecordService.clearCurrentUserId();
    }

    @Nested
    @DisplayName("基本場景")
    class BasicScenarios {

        @Test
        @DisplayName("無強制平倉 → 不發告警")
        void noForceOrders_noAlert() {
            when(binanceFuturesService.getForceOrders()).thenReturn("[]");

            task.scheduledLiquidationCheck();

            verify(notificationService, never()).sendNotificationToAdmins(any(), any(), anyInt());
        }

        @Test
        @DisplayName("API 失敗 → 不拋異常")
        void apiFailure_doesNotThrow() {
            when(binanceFuturesService.getForceOrders()).thenThrow(new RuntimeException("API error"));

            assertThatCode(() -> task.scheduledLiquidationCheck()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("偵測到強制平倉 → 記錄事件 + 標記 Trade + 發告警")
        void detectsLiquidation_fullFlow() {
            long recentTime = Instant.now().toEpochMilli() - 60_000; // 1 分鐘前
            String response = String.format(
                    "[{\"orderId\":\"12345\",\"symbol\":\"BTCUSDT\",\"side\":\"SELL\"," +
                    "\"avgPrice\":65000.0,\"origQty\":0.001,\"time\":%d}]", recentTime);
            when(binanceFuturesService.getForceOrders()).thenReturn(response);

            task.scheduledLiquidationCheck();

            verify(tradeRecordService).recordOrderEvent(eq("BTCUSDT"), eq("LIQUIDATION_DETECTED"), isNull(), any());
            verify(tradeRecordService).markTradeClosedByLiquidation("BTCUSDT");
            verify(notificationService).sendNotificationToAdmins(
                    eq("🚨 強制平倉偵測"), contains("BTCUSDT"), eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("歷史強制平倉（>15 分鐘前）→ 忽略")
        void oldForceOrder_ignored() {
            long oldTime = Instant.now().toEpochMilli() - (20 * 60 * 1000); // 20 分鐘前
            String response = String.format(
                    "[{\"orderId\":\"99999\",\"symbol\":\"ETHUSDT\",\"side\":\"BUY\"," +
                    "\"avgPrice\":3000.0,\"origQty\":0.1,\"time\":%d}]", oldTime);
            when(binanceFuturesService.getForceOrders()).thenReturn(response);

            task.scheduledLiquidationCheck();

            verify(tradeRecordService, never()).markTradeClosedByLiquidation(any());
        }
    }

    @Nested
    @DisplayName("去重")
    class Deduplication {

        @Test
        @DisplayName("相同 orderId 不重複處理")
        void sameOrderId_notProcessedTwice() {
            long recentTime = Instant.now().toEpochMilli() - 60_000;
            String response = String.format(
                    "[{\"orderId\":\"dup-1\",\"symbol\":\"BTCUSDT\",\"side\":\"SELL\"," +
                    "\"avgPrice\":65000.0,\"origQty\":0.001,\"time\":%d}]", recentTime);
            when(binanceFuturesService.getForceOrders()).thenReturn(response);

            // 第一次
            task.scheduledLiquidationCheck();
            // 第二次
            task.scheduledLiquidationCheck();

            // markTradeClosedByLiquidation 只呼叫一次
            verify(tradeRecordService, times(1)).markTradeClosedByLiquidation("BTCUSDT");
        }
    }

    @Nested
    @DisplayName("多用戶模式")
    class MultiUserMode {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("遍歷用戶查詢 forceOrders")
        void loopsUsersWithPerUserKeys() {
            User user = User.builder().userId("user-a").enabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserBinanceKeys("user-a"))
                    .thenReturn(Optional.of(new BinanceKeys("key", "secret")));
            when(binanceFuturesService.getForceOrders()).thenReturn("[]");

            task.scheduledLiquidationCheck();

            verify(userApiKeyService).getUserBinanceKeys("user-a");
            verify(binanceFuturesService).getForceOrders();
        }

        @Test
        @DisplayName("偵測到強制平倉 → 發 per-user + admin 告警")
        void detectsLiquidation_sendsPerUserAndAdmin() {
            User user = User.builder().userId("user-a").enabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserBinanceKeys("user-a"))
                    .thenReturn(Optional.of(new BinanceKeys("key", "secret")));

            long recentTime = Instant.now().toEpochMilli() - 60_000;
            String response = String.format(
                    "[{\"orderId\":\"mu-1\",\"symbol\":\"BTCUSDT\",\"side\":\"SELL\"," +
                    "\"avgPrice\":65000.0,\"origQty\":0.001,\"time\":%d}]", recentTime);
            when(binanceFuturesService.getForceOrders()).thenReturn(response);

            task.scheduledLiquidationCheck();

            verify(notificationService).sendNotificationToUser(eq("user-a"),
                    eq("🚨 強制平倉偵測"), contains("BTCUSDT"), eq(NotificationService.COLOR_RED));
            verify(notificationService).sendNotificationToAdmins(
                    eq("🚨 強制平倉偵測"), contains("BTCUSDT"), eq(NotificationService.COLOR_RED));
        }
    }
}
