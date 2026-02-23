package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.notification.service.DiscordWebhookService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderEventHandler 獨立單元測試
 *
 * 覆蓋：
 * - SL/TP FILLED → recordCloseFromStream + 通知
 * - SL/TP CANCELED/EXPIRED → recordProtectionLost + 告警
 * - PARTIALLY_FILLED → recordOrderEvent + 部分成交告警
 * - 缺 'o' 欄位 → 安全忽略
 * - recordCloseFromStream 拋異常 → 黃色告警
 * - per-user NotificationSender 路由
 * - MARKET/LIMIT FILLED → 不觸發平倉
 * - logPrefix 正確附加
 */
class OrderEventHandlerTest {

    private TradeRecordService tradeRecordService;
    private SymbolLockRegistry symbolLockRegistry;
    private OrderEventHandler.NotificationSender notificationSender;
    private final Gson gson = new Gson();

    // 捕獲通知內容
    private String lastTitle;
    private String lastMessage;
    private int lastColor;

    @BeforeEach
    void setUp() {
        tradeRecordService = mock(TradeRecordService.class);
        symbolLockRegistry = new SymbolLockRegistry();

        notificationSender = (title, message, color) -> {
            lastTitle = title;
            lastMessage = message;
            lastColor = color;
        };

        lastTitle = null;
        lastMessage = null;
        lastColor = 0;
    }

    // ==================== SL/TP FILLED ====================

    @Nested
    @DisplayName("SL/TP FILLED — 平倉記錄")
    class SlTpFilled {

        @Test
        @DisplayName("STOP_MARKET FILLED → recordCloseFromStream('SL_TRIGGERED') + 紅色通知")
        void stopMarketFilledTriggersSlClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(18.6), eq(-1000.0),
                    eq("123456789"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止損觸發");
            assertThat(lastMessage).contains("BTCUSDT");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET FILLED → recordCloseFromStream('TP_TRIGGERED') + 綠色通知")
        void takeProfitFilledTriggersTpClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "FILLED", "BUY",
                    3500.0, 1.0, 1.4, "USDT", 200.0, 987654321L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("ETHUSDT"), eq(3500.0), eq(1.0),
                    eq(1.4), eq(200.0),
                    eq("987654321"), eq("TP_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止盈觸發");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_GREEN);
        }
    }

    // ==================== CANCELED / EXPIRED ====================

    @Nested
    @DisplayName("SL/TP CANCELED/EXPIRED — 保護消失")
    class ProtectionLost {

        @Test
        @DisplayName("STOP_MARKET CANCELED → recordProtectionLost + 紅色告警")
        void slCanceledTriggersRedAlert() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "CANCELED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 555666777L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("555666777"), eq("CANCELED"));

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());

            assertThat(lastTitle).contains("止損單被取消");
            assertThat(lastMessage).contains("持倉已失去止損保護");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET CANCELED → recordProtectionLost + 黃色告警")
        void tpCanceledTriggersYellowAlert() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 888999000L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("888999000"), eq("CANCELED"));

            assertThat(lastTitle).contains("止盈單被取消");
            assertThat(lastMessage).contains("止損仍有效");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_YELLOW);
        }

        @Test
        @DisplayName("STOP_MARKET EXPIRED → 與 CANCELED 同等處理（紅色告警）")
        void slExpiredTriggersRedAlert() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "EXPIRED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("111222333"), eq("EXPIRED"));

            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET EXPIRED → 黃色告警")
        void tpExpiredTriggersYellowAlert() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "EXPIRED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 444555666L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("444555666"), eq("EXPIRED"));

            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_YELLOW);
        }
    }

    // ==================== PARTIALLY_FILLED ====================

    @Nested
    @DisplayName("SL/TP PARTIALLY_FILLED — 部分成交")
    class PartialFill {

        @Test
        @DisplayName("STOP_MARKET PARTIALLY_FILLED → recordOrderEvent + 黃色告警")
        void slPartialFillTriggersWarning() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildPartialFillEvent(
                    "BTCUSDT", "STOP_MARKET", "SELL", 0.3, 0.5, 111L);

            handler.handleOrderTradeUpdate(event);

            // 不應觸發平倉記錄
            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());

            // 應記錄部分成交事件
            verify(tradeRecordService).recordOrderEvent(
                    eq("BTCUSDT"),
                    eq("SL_PARTIAL_FILL"),
                    isNull(),
                    anyString());

            assertThat(lastTitle).contains("止損");
            assertThat(lastTitle).contains("部分成交");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_YELLOW);
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET PARTIALLY_FILLED → recordOrderEvent + 黃色告警")
        void tpPartialFillTriggersWarning() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildPartialFillEvent(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "BUY", 0.5, 1.0, 222L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordOrderEvent(
                    eq("ETHUSDT"),
                    eq("TP_PARTIAL_FILL"),
                    isNull(),
                    anyString());

            assertThat(lastTitle).contains("止盈");
            assertThat(lastTitle).contains("部分成交");
        }
    }

    // ==================== 非 SL/TP 類型 ====================

    @Nested
    @DisplayName("MARKET/LIMIT FILLED — 不觸發平倉")
    class NonSlTpTypes {

        @Test
        @DisplayName("LIMIT FILLED → 不呼叫 recordCloseFromStream")
        void limitFilledDoesNotTriggerClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("MARKET FILLED → 不呼叫 recordCloseFromStream")
        void marketFilledDoesNotTriggerClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 444555666L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("LIMIT CANCELED → 不觸發保護消失告警（入場單取消是正常操作）")
        void limitCanceledIgnored() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 777888999L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString());

            // 不應有任何通知
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("STOP_MARKET NEW → 忽略（非 FILLED 狀態）")
        void stopMarketNewIgnored() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "NEW", "SELL",
                    93000.0, 0.5, 0.0, "USDT", 0.0, 999000111L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }
    }

    // ==================== 非 USDT 手續費 ====================

    @Nested
    @DisplayName("非 USDT 手續費 fallback")
    class NonUsdtCommission {

        @Test
        @DisplayName("手續費幣種 BNB → fallback 估算 (avgPrice × qty × 0.04%)")
        void bnbCommissionFallback() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 0.01, "BNB", -1000.0, 777888999L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            double expectedCommission = 93000.0 * 0.5 * 0.0004;  // 18.6
            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(expectedCommission), eq(-1000.0),
                    eq("777888999"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));
        }
    }

    // ==================== 安全處理 ====================

    @Nested
    @DisplayName("邊界條件與安全處理")
    class SafetyHandling {

        @Test
        @DisplayName("缺少 'o' 欄位 → 安全忽略，不拋異常")
        void missingOrderFieldIgnored() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = new JsonObject();
            event.addProperty("e", "ORDER_TRADE_UPDATE");
            // 沒有 "o" 欄位

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("recordCloseFromStream 拋異常 → 不傳播 + 發黃色告警")
        void streamCloseFailureSendsWarning() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordCloseFromStream(
                            anyString(), anyDouble(), anyDouble(),
                            anyDouble(), anyDouble(),
                            anyString(), anyString(), anyLong());

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            assertThat(lastTitle).contains("平倉記錄失敗");
            assertThat(lastMessage).contains("DB error");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_YELLOW);
        }

        @Test
        @DisplayName("recordProtectionLost 拋異常 → 仍發通知不傳播")
        void protectionLostFailureStillNotifies() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordProtectionLost(
                            anyString(), anyString(), anyString(), anyString());

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "CANCELED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            // 即使 recordProtectionLost 失敗，通知仍應發出
            assertThat(lastTitle).contains("止損單被取消");
        }

        @Test
        @DisplayName("recordOrderEvent 拋異常 → 不影響部分成交通知")
        void partialFillEventFailureStillNotifies() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordOrderEvent(
                            anyString(), anyString(), any(), anyString());

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, "");

            JsonObject event = buildPartialFillEvent(
                    "BTCUSDT", "STOP_MARKET", "SELL", 0.3, 0.5, 111L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            assertThat(lastTitle).contains("部分成交");
        }
    }

    // ==================== Per-User NotificationSender ====================

    @Nested
    @DisplayName("Per-User 通知路由")
    class PerUserNotification {

        @Test
        @DisplayName("per-user NotificationSender 正確路由通知")
        void perUserNotificationSenderRoutes() {
            AtomicReference<String> capturedUserId = new AtomicReference<>();
            AtomicReference<String> capturedTitle = new AtomicReference<>();

            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);
            doAnswer(invocation -> {
                capturedUserId.set(invocation.getArgument(0));
                capturedTitle.set(invocation.getArgument(1));
                return null;
            }).when(mockWebhook).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

            // 模擬 per-user 版本的 NotificationSender（同 MultiUserDataStreamManager 的建構方式）
            String userId = "user-abc";
            OrderEventHandler.NotificationSender perUserSender =
                    (title, msg, color) -> mockWebhook.sendNotificationToUser(userId, title, msg, color);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, perUserSender, gson, "用戶 " + userId + " ");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            assertThat(capturedUserId.get()).isEqualTo("user-abc");
            assertThat(capturedTitle.get()).contains("止損觸發");
        }
    }

    // ==================== logPrefix ====================

    @Nested
    @DisplayName("logPrefix 行為")
    class LogPrefix {

        @Test
        @DisplayName("null logPrefix → 預設空字串（不拋 NPE）")
        void nullLogPrefixSafe() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, gson, null);

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 111222333L, 1700000000000L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== SymbolLock ====================

    @Nested
    @DisplayName("SymbolLock 併發保護")
    class SymbolLockTests {

        @Test
        @DisplayName("processStreamClose 使用 SymbolLockRegistry per-symbol lock")
        void processStreamCloseUsesLock() {
            SymbolLockRegistry spyRegistry = spy(new SymbolLockRegistry());
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, spyRegistry, notificationSender, gson, "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(spyRegistry).getLock("BTCUSDT");
        }
    }

    // ==================== 輔助方法 ====================

    private JsonObject buildOrderTradeUpdate(String symbol, String orderType, String orderStatus,
                                              String side, double avgPrice, double filledQty,
                                              double commission, String commissionAsset,
                                              double realizedProfit, long orderId,
                                              long transactionTime) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("o", orderType);
        order.addProperty("X", orderStatus);
        order.addProperty("S", side);
        order.addProperty("ap", avgPrice);
        order.addProperty("z", filledQty);
        order.addProperty("n", commission);
        order.addProperty("N", commissionAsset);
        order.addProperty("rp", realizedProfit);
        order.addProperty("i", orderId);
        order.addProperty("T", transactionTime);
        order.addProperty("q", filledQty);  // origQty for partial fill

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);

        return event;
    }

    private JsonObject buildPartialFillEvent(String symbol, String orderType, String side,
                                              double filledQty, double origQty, long orderId) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("o", orderType);
        order.addProperty("X", "PARTIALLY_FILLED");
        order.addProperty("S", side);
        order.addProperty("ap", 0.0);
        order.addProperty("z", filledQty);
        order.addProperty("n", 0.0);
        order.addProperty("N", "USDT");
        order.addProperty("rp", 0.0);
        order.addProperty("i", orderId);
        order.addProperty("T", 1700000000000L);
        order.addProperty("q", origQty);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);

        return event;
    }
}
