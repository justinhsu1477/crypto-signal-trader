package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.entity.Trade;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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

        @Test
        @DisplayName("exchangeName 非空時，通知 body 包含交易所名稱")
        void notificationIncludesExchangeName() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "BINANCE");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            assertThat(lastMessage).contains("交易所: BINANCE");
        }
    }

    // ==================== CANCELED / EXPIRED ====================

    @Nested
    @DisplayName("SL/TP CANCELED/EXPIRED — 保護消失")
    class ProtectionLost {

        @Test
        @DisplayName("STOP_MARKET CANCELED（仍有持倉）→ recordProtectionLost + log only（不發 Discord）")
        void slCanceledTriggersRedAlert() {
            // 模擬仍有 OPEN 持倉 → 降級為 log only
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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

            // 保護消失降級為 log only，不發 Discord 通知
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET CANCELED（仍有持倉）→ recordProtectionLost + log only")
        void tpCanceledTriggersYellowAlert() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 888999000L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("888999000"), eq("CANCELED"));

            // 保護消失降級為 log only
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("STOP_MARKET EXPIRED（仍有持倉）→ recordProtectionLost + log only")
        void slExpiredTriggersRedAlert() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "EXPIRED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("111222333"), eq("EXPIRED"));

            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET EXPIRED（仍有持倉）→ log only")
        void tpExpiredTriggersYellowAlert() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "EXPIRED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 444555666L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("444555666"), eq("EXPIRED"));

            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("STOP_MARKET EXPIRED 但倉位已平 → 不發告警通知（正常連帶過期）")
        void slExpiredAfterCloseNoAlarm() {
            // 模擬倉位已平，recordProtectionLost 回傳 false
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "EXPIRED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            // recordProtectionLost 仍被呼叫（事件仍需記錄到 DB）
            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("111222333"), eq("EXPIRED"));

            // 但不應發送任何通知
            assertThat(lastTitle).isNull();
            assertThat(lastMessage).isNull();
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET CANCELED 但倉位已平 → 不發告警通知")
        void tpCanceledAfterCloseNoAlarm() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 888999000L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("888999000"), eq("CANCELED"));

            assertThat(lastTitle).isNull();
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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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

    // ==================== LIMIT FILLED — 入場成交 / 平倉 fallback ====================

    @Nested
    @DisplayName("LIMIT FILLED — 入場成交通知 + 平倉 fallback")
    class LimitFilled {

        @Test
        @DisplayName("LIMIT FILLED + entryOrderId 匹配 → recordLimitEntryFilled + 綠色通知")
        void limitFilledEntryOrder() {
            Trade mockTrade = Trade.builder()
                    .tradeId("test-trade-1")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .build();
            when(tradeRecordService.recordLimitEntryFilled(
                    eq("BTCUSDT"), eq("111222333"), eq(95000.0),
                    eq(0.5), eq(9.5), eq(1700000000000L)))
                    .thenReturn(mockTrade);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordLimitEntryFilled(
                    eq("BTCUSDT"), eq("111222333"), eq(95000.0),
                    eq(0.5), eq(9.5), eq(1700000000000L));
            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());

            assertThat(lastTitle).isEqualTo("✅ 限價入場成交");
            assertThat(lastMessage).contains("BTCUSDT");
            assertThat(lastMessage).contains("LONG");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_GREEN);
        }

        @Test
        @DisplayName("LIMIT FILLED + entryOrderId 不匹配 → processStreamClose (SIGNAL_CLOSE)")
        void limitFilledCloseOrder() {
            when(tradeRecordService.recordLimitEntryFilled(
                    anyString(), anyString(), anyDouble(),
                    anyDouble(), anyDouble(), anyLong()))
                    .thenReturn(null);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "FILLED", "SELL",
                    95000.0, 0.5, 19.0, "USDT", 500.0, 999888777L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(95000.0), eq(0.5),
                    eq(19.0), eq(500.0),
                    eq("999888777"), eq("SIGNAL_CLOSE"),
                    eq(1700000000000L));
        }

        @Test
        @DisplayName("LIMIT FILLED 入場成交 + adminNotifier 存在 → admin 也收到通知")
        void limitFilledEntryWithAdminNotifier() {
            Trade mockTrade = Trade.builder()
                    .tradeId("test-trade-2")
                    .symbol("ETHUSDT")
                    .side("SHORT")
                    .build();
            when(tradeRecordService.recordLimitEntryFilled(
                    eq("ETHUSDT"), eq("222333444"), eq(3500.0),
                    eq(1.0), eq(1.4), eq(1700000000000L)))
                    .thenReturn(mockTrade);

            // Admin notifier 捕獲
            AtomicReference<String> adminTitle = new AtomicReference<>();
            AtomicReference<String> adminMsg = new AtomicReference<>();
            OrderEventHandler.NotificationSender adminSender = (title, msg, color) -> {
                adminTitle.set(title);
                adminMsg.set(msg);
            };

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminSender, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "LIMIT", "FILLED", "SELL",
                    3500.0, 1.0, 1.4, "USDT", 0.0, 222333444L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            // 用戶通知
            assertThat(lastTitle).isEqualTo("✅ 限價入場成交");

            // Admin 通知
            assertThat(adminTitle.get()).isEqualTo("✅ 限價入場成交");
            assertThat(adminMsg.get()).contains("ETHUSDT");
            assertThat(adminMsg.get()).contains("SHORT");
        }
    }

    // ==================== 非 SL/TP 類型 ====================

    @Nested
    @DisplayName("MARKET FILLED — 無 Algo hint 時不觸發平倉")
    class NonSlTpTypes {

        @Test
        @DisplayName("MARKET FILLED → 不呼叫 recordCloseFromStream")
        void marketFilledDoesNotTriggerClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
        @DisplayName("recordProtectionLost 拋異常 → 保守假設有持倉，log only 不傳播")
        void protectionLostFailureStillNotifies() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordProtectionLost(
                            anyString(), anyString(), anyString(), anyString());

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "CANCELED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            // 保護消失降級為 log only，即使 DB 異常也不發 Discord
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("recordOrderEvent 拋異常 → 不影響部分成交通知")
        void partialFillEventFailureStillNotifies() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordOrderEvent(
                            anyString(), anyString(), any(), anyString());

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

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
                    tradeRecordService, symbolLockRegistry, perUserSender, null, gson, "用戶 " + userId + " ", "");

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
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, null, "");

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
                    tradeRecordService, spyRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            verify(spyRegistry).getLock("BTCUSDT");
        }
    }

    // ==================== ALGO_UPDATE 事件 ====================

    @Nested
    @DisplayName("ALGO_UPDATE — Algo 訂單狀態變更")
    class AlgoUpdateTests {

        @Test
        @DisplayName("ALGO_UPDATE TRIGGERED STOP_MARKET → 暫存 SL_TRIGGERED hint")
        void algoTriggeredSl_storesPendingHint() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED", 12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(event);

            // 不應直接觸發 recordCloseFromStream（要等 ORDER_TRADE_UPDATE MARKET）
            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("ALGO_UPDATE TRIGGERED TAKE_PROFIT_MARKET → 暫存 TP_TRIGGERED hint")
        void algoTriggeredTp_storesPendingHint() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildAlgoUpdate("ETHUSDT", "TAKE_PROFIT_MARKET", "TRIGGERED", 67890L, "TP-1700000-c3d4");
            handler.handleAlgoUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("ALGO_UPDATE CANCELED（仍有持倉）→ recordProtectionLost + log only")
        void algoCanceled_triggersProtectionLost() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // Binance 用美式拼法 CANCELED（單 L）
            JsonObject event = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED", 12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("12345"), eq("CANCELED"));

            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("ALGO_UPDATE EXPIRED（仍有持倉）→ recordProtectionLost + log only")
        void algoExpired_triggersProtectionLost() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildAlgoUpdate("ETHUSDT", "TAKE_PROFIT_MARKET", "EXPIRED", 67890L, "TP-1700000-c3d4");
            handler.handleAlgoUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("67890"), eq("EXPIRED"));

            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("ALGO_UPDATE REJECTED（仍有持倉）→ 無條件 recordProtectionLost + log only")
        void algoRejected_triggersProtectionLost() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "REJECTED", 12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("12345"), eq("REJECTED"));

            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("ALGO_UPDATE REJECTED 即使有 sibling hint 也走 protectionLost（非 OCO 行為）")
        void algoRejected_alwaysAlerts_evenWithSibling() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // TP 已觸發（sibling hint 存在）
            JsonObject tpTriggered = buildAlgoUpdate("BTCUSDT", "TAKE_PROFIT_MARKET", "TRIGGERED",
                    200L, "TP-1700000-c3d4", "77001");
            handler.handleAlgoUpdate(tpTriggered);

            // SL 被 REJECTED（而非 CANCELED）→ 無條件走 protectionLost，不走 OCO 跳過
            JsonObject slRejected = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "REJECTED",
                    100L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(slRejected);

            // REJECTED 應無條件走 protectionLost（log only）
            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("100"), eq("REJECTED"));
        }

        @Test
        @DisplayName("ALGO_UPDATE 缺少 'o' 欄位 → 安全忽略")
        void algoUpdateMissingOrderField() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = new JsonObject();
            event.addProperty("e", "ALGO_UPDATE");
            // 沒有 "o" 欄位

            assertThatCode(() -> handler.handleAlgoUpdate(event))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== ALGO_UPDATE + ORDER_TRADE_UPDATE 整合 ====================

    @Nested
    @DisplayName("Algo 觸發 → MARKET 成交整合測試")
    class AlgoMarketIntegration {

        @Test
        @DisplayName("TRIGGERED(SL) + orderId 精確匹配 MARKET FILLED → SL_TRIGGERED")
        void algoSlTriggeredWithOrderIdMatch() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ALGO_UPDATE TRIGGERED 帶 ai=999888777（觸發後 MARKET 單的 orderId）
            JsonObject algoEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    12345L, "SL-1700000-a1b2", "999888777");
            handler.handleAlgoUpdate(algoEvent);

            // ORDER_TRADE_UPDATE MARKET FILLED，orderId=999888777 與 hint 的 ai 匹配
            JsonObject marketEvent = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketEvent);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(18.6), eq(-1000.0),
                    eq("999888777"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止損觸發");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("TRIGGERED(TP) + orderId 精確匹配 MARKET FILLED → TP_TRIGGERED")
        void algoTpTriggeredWithOrderIdMatch() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject algoEvent = buildAlgoUpdate("ETHUSDT", "TAKE_PROFIT_MARKET", "TRIGGERED",
                    67890L, "TP-1700000-c3d4", "111222333");
            handler.handleAlgoUpdate(algoEvent);

            JsonObject marketEvent = buildOrderTradeUpdate(
                    "ETHUSDT", "MARKET", "FILLED", "BUY",
                    3500.0, 1.0, 1.4, "USDT", 200.0, 111222333L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketEvent);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("ETHUSDT"), eq(3500.0), eq(1.0),
                    eq(1.4), eq(200.0),
                    eq("111222333"), eq("TP_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止盈觸發");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_GREEN);
        }

        @Test
        @DisplayName("TRIGGERED 但 MARKET orderId 不匹配（手動市價單）→ 不觸發平倉")
        void algoTriggeredButOrderIdMismatch_noClose() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ALGO_UPDATE 帶 ai=999888777
            JsonObject algoEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    12345L, "SL-1700000-a1b2", "999888777");
            handler.handleAlgoUpdate(algoEvent);

            // 先到一個不同 orderId 的 MARKET FILLED（手動市價平倉）
            JsonObject manualMarket = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93500.0, 0.3, 11.2, "USDT", -500.0, 111111111L, 1700000000000L);
            handler.handleOrderTradeUpdate(manualMarket);

            // 不應觸發平倉（orderId 不匹配）
            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("TRIGGERED(ai 為空) → fallback 到 symbol 匹配")
        void algoTriggeredEmptyAi_fallbackToSymbolMatch() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ai 為空（罕見情況）
            JsonObject algoEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    12345L, "SL-1700000-a1b2", "");
            handler.handleAlgoUpdate(algoEvent);

            // 任何同 symbol 的 MARKET FILLED 都會匹配（因為 ai 為空 → fallback）
            JsonObject marketEvent = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketEvent);

            verify(tradeRecordService).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), eq("SL_TRIGGERED"), anyLong());
        }

        @Test
        @DisplayName("TRIGGERING 不寫入 hint → MARKET FILLED 不觸發平倉")
        void triggeringDoesNotStoreHint() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // TRIGGERING（觸發中）不寫入 hint
            JsonObject triggeringEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERING",
                    12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(triggeringEvent);

            // MARKET FILLED → 沒有 hint → 不觸發平倉
            JsonObject marketEvent = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketEvent);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("TRIGGERED → CANCELED → hint 被清除 → 後續 MARKET 不誤判")
        void canceledClearsStaleHint() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // TRIGGERED 存入 hint
            JsonObject triggeredEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    12345L, "SL-1700000-a1b2", "999888777");
            handler.handleAlgoUpdate(triggeredEvent);

            // CANCELED 清除 stale hint
            JsonObject canceledEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED",
                    12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(canceledEvent);

            // 後續 MARKET FILLED → hint 已被清除 → 不觸發平倉
            JsonObject marketEvent = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketEvent);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("MARKET FILLED with clientOrderId SL- 前綴 (fallback) → SL_TRIGGERED")
        void marketFilledWithSlClientIdFallback() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // 沒有先收 ALGO_UPDATE，直接收 MARKET FILLED（with SL- clientOrderId）
            JsonObject event = buildOrderTradeUpdateWithClientId(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L,
                    "SL-1700000-a1b2");
            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(18.6), eq(-1000.0),
                    eq("999888777"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));
        }

        @Test
        @DisplayName("MARKET FILLED with clientOrderId TP- 前綴 (fallback) → TP_TRIGGERED")
        void marketFilledWithTpClientIdFallback() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdateWithClientId(
                    "ETHUSDT", "MARKET", "FILLED", "BUY",
                    3500.0, 1.0, 1.4, "USDT", 200.0, 111222333L, 1700000000000L,
                    "TP-1700000-c3d4");
            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("ETHUSDT"), eq(3500.0), eq(1.0),
                    eq(1.4), eq(200.0),
                    eq("111222333"), eq("TP_TRIGGERED"),
                    eq(1700000000000L));
        }

        @Test
        @DisplayName("MARKET FILLED 無 algo hint 且無 SL/TP 前綴 → 不觸發平倉")
        void marketFilledNoAlgoHintNoPrefix() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdateWithClientId(
                    "BTCUSDT", "MARKET", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 444555666L, 1700000000000L,
                    "web_abc123");
            handler.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("TP TRIGGERED → SL CANCELED(OCO 連帶) → MARKET FILLED → 仍正確記錄 TP_TRIGGERED 且不發假告警")
        void tpTriggeredSlCanceledOcoRaceCondition() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ① TP 觸發 (algoId=200, ai=77001)
            JsonObject tpTriggered = buildAlgoUpdate("BTCUSDT", "TAKE_PROFIT_MARKET", "TRIGGERED",
                    200L, "TP-1700000-c3d4", "77001");
            handler.handleAlgoUpdate(tpTriggered);

            // ② SL 被 Binance 自動取消 (algoId=100, 不同 algo)
            JsonObject slCanceled = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED",
                    100L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(slCanceled);

            // 不應發 SL 保護消失告警（因為同 symbol 有已觸發的 TP hint → OCO 連帶取消）
            verify(tradeRecordService, never()).recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString());
            assertThat(lastTitle).isNull();  // 無告警

            // ③ TP 的 MARKET 單成交 (orderId=77001)
            JsonObject marketFilled = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    98000.0, 0.5, 19.6, "USDT", 2500.0, 77001L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketFilled);

            // 應正確記錄 TP_TRIGGERED（hint 沒被 SL CANCELED 誤刪）
            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(98000.0), eq(0.5),
                    eq(19.6), eq(2500.0),
                    eq("77001"), eq("TP_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止盈觸發");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_GREEN);
        }

        @Test
        @DisplayName("SL TRIGGERED → TP CANCELED(OCO 連帶) → MARKET FILLED → 仍正確記錄 SL_TRIGGERED 且不發假告警")
        void slTriggeredTpCanceledOcoRaceCondition() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ① SL 觸發 (algoId=100, ai=88001)
            JsonObject slTriggered = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    100L, "SL-1700000-a1b2", "88001");
            handler.handleAlgoUpdate(slTriggered);

            // ② TP 被 Binance 自動取消 (algoId=200)
            JsonObject tpCanceled = buildAlgoUpdate("BTCUSDT", "TAKE_PROFIT_MARKET", "CANCELED",
                    200L, "TP-1700000-c3d4");
            handler.handleAlgoUpdate(tpCanceled);

            // 不應發告警
            verify(tradeRecordService, never()).recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString());

            // ③ SL 的 MARKET 單成交 (orderId=88001)
            JsonObject marketFilled = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    91000.0, 0.5, 18.2, "USDT", -2000.0, 88001L, 1700000000000L);
            handler.handleOrderTradeUpdate(marketFilled);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(91000.0), eq(0.5),
                    eq(18.2), eq(-2000.0),
                    eq("88001"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));

            assertThat(lastTitle).contains("止損觸發");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("單獨 SL CANCELED（無 sibling hint）→ 應正常觸發 protectionLost（log only）")
        void slCanceledAloneTriggersAlert() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // 單獨取消（非 OCO 連帶），沒有其他 sibling hint
            JsonObject slCanceled = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED",
                    100L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(slCanceled);

            // 應走 protectionLost（log only）
            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("100"), eq("CANCELED"));
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("pending hint 消費後不重複使用")
        void pendingHintConsumedOnce() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            // ALGO_UPDATE → 存入 hint（ai=999888777）
            JsonObject algoEvent = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "TRIGGERED",
                    12345L, "SL-1700000-a1b2", "999888777");
            handler.handleAlgoUpdate(algoEvent);

            // 第一次 MARKET FILLED（orderId=999888777）→ 消費 hint
            JsonObject market1 = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 999888777L, 1700000000000L);
            handler.handleOrderTradeUpdate(market1);

            // 第二次 MARKET FILLED（同 symbol，不同 orderId）→ 不應再觸發平倉
            JsonObject market2 = buildOrderTradeUpdate(
                    "BTCUSDT", "MARKET", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 444555666L, 1700000000000L);
            handler.handleOrderTradeUpdate(market2);

            // 只觸發一次 recordCloseFromStream
            verify(tradeRecordService, times(1)).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }
    }

    // ==================== Admin 通知 ====================

    @Nested
    @DisplayName("Admin 通知 — adminNotifier")
    class AdminNotification {

        private String adminTitle;
        private String adminMessage;
        private int adminColor;

        private OrderEventHandler.NotificationSender adminNotifier;

        @BeforeEach
        void setUpAdmin() {
            adminTitle = null;
            adminMessage = null;
            adminColor = 0;
            adminNotifier = (title, message, color) -> {
                adminTitle = title;
                adminMessage = message;
                adminColor = color;
            };
        }

        @Test
        @DisplayName("SL 觸發 — adminNotifier 收到損益摘要通知")
        void slTriggered_notifiesAdmin() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminNotifier, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            // per-user 通知包含完整明細
            assertThat(lastTitle).contains("止損觸發");
            assertThat(lastMessage).contains("數量");

            // Admin 通知包含損益摘要
            assertThat(adminTitle).contains("止損觸發");
            assertThat(adminMessage).contains("BTCUSDT");
            assertThat(adminMessage).contains("-1000.00");
            assertThat(adminColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("TP 觸發 — adminNotifier 收到損益摘要通知")
        void tpTriggered_notifiesAdmin() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminNotifier, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "FILLED", "BUY",
                    3500.0, 1.0, 1.4, "USDT", 200.0, 987654321L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            assertThat(adminTitle).contains("止盈觸發");
            assertThat(adminMessage).contains("+200.00");
            assertThat(adminColor).isEqualTo(DiscordWebhookService.COLOR_GREEN);
        }

        @Test
        @DisplayName("保護消失 — 降級為 log only，admin 也不收到通知")
        void protectionLost_logOnly_noAdminNotification() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminNotifier, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "CANCELED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 555666777L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            // 保護消失降級為 log only，per-user 和 admin 都不發
            assertThat(lastTitle).isNull();
            assertThat(adminTitle).isNull();
        }

        @Test
        @DisplayName("部分成交 — adminNotifier 收到告警")
        void partialFill_notifiesAdmin() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminNotifier, gson, "", "");

            JsonObject event = buildPartialFillEvent(
                    "BTCUSDT", "STOP_MARKET", "SELL", 0.3, 0.5, 111L);

            handler.handleOrderTradeUpdate(event);

            assertThat(adminTitle).contains("部分成交");
            assertThat(adminColor).isEqualTo(DiscordWebhookService.COLOR_YELLOW);
        }

        @Test
        @DisplayName("adminNotifier 為 null — 不拋異常（單用戶模式）")
        void nullAdminNotifier_doesNotThrow() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            assertThatCode(() -> handler.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            // per-user 通知仍正常
            assertThat(lastTitle).contains("止損觸發");
        }

        @Test
        @DisplayName("保護消失但倉位已平 — adminNotifier 也不發通知")
        void protectionLostNoPosition_noAdminNotification() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, adminNotifier, gson, "", "");

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "EXPIRED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            handler.handleOrderTradeUpdate(event);

            // 倉位已平 → 不發告警（per-user 和 admin 都不發）
            assertThat(lastTitle).isNull();
            assertThat(adminTitle).isNull();
        }
    }

    // ==================== handleProtectionLost SymbolLock ====================

    @Nested
    @DisplayName("handleProtectionLost — SymbolLock 與 CANCEL 同步")
    class ProtectionLostLock {

        @Test
        @DisplayName("鎖被佔住（CANCEL 進行中）→ 超時跳過，不呼叫 recordProtectionLost")
        void lockHeld_skipsProcessing() throws InterruptedException {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");
            // 設短超時避免測試等 3 秒
            handler.protectionLostLockTimeoutMs = 100;

            // 預先佔住 BTCUSDT 的鎖（模擬 CANCEL 持鎖中）
            ReentrantLock lock = symbolLockRegistry.getLock("BTCUSDT");
            lock.lock();
            try {
                // 在另一個執行緒觸發 ALGO_UPDATE CANCELED
                Thread t = new Thread(() -> {
                    JsonObject event = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED",
                            12345L, "SL-1700000-a1b2");
                    handler.handleAlgoUpdate(event);
                });
                t.start();
                t.join(2000);  // 等待最多 2 秒（含 100ms tryLock 超時）

                // tryLock 超時 → 不應呼叫 recordProtectionLost
                verify(tradeRecordService, never()).recordProtectionLost(
                        anyString(), anyString(), anyString(), anyString());
            } finally {
                lock.unlock();
            }
        }

        @Test
        @DisplayName("鎖可用 → 正常呼叫 recordProtectionLost")
        void lockAvailable_normalProcessing() {
            when(tradeRecordService.recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "");
            handler.protectionLostLockTimeoutMs = 100;

            // 鎖沒被佔 → 正常走 recordProtectionLost
            JsonObject event = buildAlgoUpdate("BTCUSDT", "STOP_MARKET", "CANCELED",
                    12345L, "SL-1700000-a1b2");
            handler.handleAlgoUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("12345"), eq("CANCELED"));
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

    private JsonObject buildOrderTradeUpdateWithClientId(String symbol, String orderType, String orderStatus,
                                                         String side, double avgPrice, double filledQty,
                                                         double commission, String commissionAsset,
                                                         double realizedProfit, long orderId,
                                                         long transactionTime, String clientOrderId) {
        JsonObject event = buildOrderTradeUpdate(symbol, orderType, orderStatus, side, avgPrice,
                filledQty, commission, commissionAsset, realizedProfit, orderId, transactionTime);
        // 加入 clientOrderId (c 欄位)
        event.getAsJsonObject("o").addProperty("c", clientOrderId);
        return event;
    }

    /**
     * 建構 ALGO_UPDATE 事件（符合 Binance 實際格式）
     * 欄位對照：s=symbol, o=orderType, X=algoStatus, aid=algoId, caid=clientAlgoId, ai=triggeredOrderId
     */
    private JsonObject buildAlgoUpdate(String symbol, String orderType, String algoStatus,
                                        long algoId, String clientAlgoId) {
        return buildAlgoUpdate(symbol, orderType, algoStatus, algoId, clientAlgoId, "");
    }

    private JsonObject buildAlgoUpdate(String symbol, String orderType, String algoStatus,
                                        long algoId, String clientAlgoId, String triggeredOrderId) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("o", orderType);   // Binance 用 "o" 代表 orderType
        order.addProperty("X", algoStatus);  // Binance 用 "X" 代表 algoStatus
        order.addProperty("aid", algoId);    // Binance 用 "aid" 代表 algoId
        order.addProperty("caid", clientAlgoId); // Binance 用 "caid" 代表 clientAlgoId
        order.addProperty("ai", triggeredOrderId); // Binance 用 "ai" 代表觸發後的 MARKET 單 orderId

        JsonObject event = new JsonObject();
        event.addProperty("e", "ALGO_UPDATE");
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

    // ==================== ACCOUNT_UPDATE — 強制平倉偵測 ====================

    @Nested
    @DisplayName("ACCOUNT_UPDATE — 強制平倉偵測")
    class AccountUpdateTests {

        private OrderEventHandler handler;

        @BeforeEach
        void initHandler() {
            handler = new OrderEventHandler(tradeRecordService, symbolLockRegistry,
                    notificationSender, null, gson, "", "");
        }

        @Test
        @DisplayName("LIQUIDATION 事件 → 記錄 + 標記 Trade + 告警")
        void liquidationEvent_fullFlow() {
            JsonObject event = buildAccountUpdateEvent("LIQUIDATION", "BTCUSDT", 0, -500.0);

            handler.handleAccountUpdate(event);

            verify(tradeRecordService).recordOrderEvent(eq("BTCUSDT"), eq("LIQUIDATION"), isNull(), any());
            verify(tradeRecordService).markTradeClosedByLiquidation("BTCUSDT");
            assertThat(lastTitle).contains("強制平倉");
            assertThat(lastColor).isEqualTo(DiscordWebhookService.COLOR_RED);
        }

        @Test
        @DisplayName("非 LIQUIDATION reason → 忽略")
        void nonLiquidationReason_ignored() {
            JsonObject event = buildAccountUpdateEvent("ORDER", "BTCUSDT", 0.001, 0);

            handler.handleAccountUpdate(event);

            verify(tradeRecordService, never()).markTradeClosedByLiquidation(any());
            assertThat(lastTitle).isNull();
        }

        @Test
        @DisplayName("無 'a' 欄位 → 安全忽略")
        void noAccountField_ignored() {
            JsonObject event = new JsonObject();
            event.addProperty("e", "ACCOUNT_UPDATE");

            handler.handleAccountUpdate(event);

            verify(tradeRecordService, never()).markTradeClosedByLiquidation(any());
        }

        @Test
        @DisplayName("LIQUIDATION 但 positionAmt != 0 → 記錄但不標記 CLOSED")
        void liquidationPartial_doesNotMarkClosed() {
            JsonObject event = buildAccountUpdateEvent("LIQUIDATION", "BTCUSDT", 0.0005, -200.0);

            handler.handleAccountUpdate(event);

            verify(tradeRecordService).recordOrderEvent(eq("BTCUSDT"), eq("LIQUIDATION"), isNull(), any());
            // positionAmt != 0 → 不標記 CLOSED
            verify(tradeRecordService, never()).markTradeClosedByLiquidation(any());
        }

        private JsonObject buildAccountUpdateEvent(String reason, String symbol,
                                                     double positionAmt, double unrealizedPnl) {
            JsonObject pos = new JsonObject();
            pos.addProperty("s", symbol);
            pos.addProperty("pa", positionAmt);
            pos.addProperty("up", unrealizedPnl);

            com.google.gson.JsonArray positions = new com.google.gson.JsonArray();
            positions.add(pos);

            JsonObject account = new JsonObject();
            account.addProperty("m", reason);
            account.add("P", positions);

            JsonObject event = new JsonObject();
            event.addProperty("e", "ACCOUNT_UPDATE");
            event.add("a", account);

            return event;
        }
    }

    // ==================== Bitget Order 事件 ====================

    @Nested
    @DisplayName("Bitget Order 事件 — handleBitgetOrder")
    class BitgetOrder {

        private OrderEventHandler handler;

        @BeforeEach
        void setUpHandler() {
            handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "BITGET");
        }

        @Test
        @DisplayName("平倉 (tradeSide=close, planType=pos_loss) → SL_TRIGGERED")
        void closeSL() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-001", "", "sell",
                    "market", "filled", "pos_loss", "API", "close",
                    93000.0, 0.25, 0.25, 5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.25), eq(5.0),
                    eq(0.0), eq("bg-001"), eq("SL_TRIGGERED"), eq(1700000000000L));
        }

        @Test
        @DisplayName("平倉 (planType=pos_profit) → TP_TRIGGERED")
        void closeTP() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-002", "", "sell",
                    "market", "filled", "pos_profit", "API", "close",
                    100000.0, 0.25, 0.25, 5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(100000.0), eq(0.25), eq(5.0),
                    eq(0.0), eq("bg-002"), eq("TP_TRIGGERED"), eq(1700000000000L));
        }

        @Test
        @DisplayName("平倉 (planType 空 + clientOid=SL-xxx) → SL_TRIGGERED")
        void closeSLByClientOid() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-003", "SL-custom", "sell",
                    "market", "filled", "", "API", "close",
                    93000.0, 0.25, 0.25, 5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), eq("bg-003"), eq("SL_TRIGGERED"), anyLong());
        }

        @Test
        @DisplayName("平倉 (planType 空 + clientOid 無前綴) → SIGNAL_CLOSE")
        void closeSignal() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-004", "custom-id", "sell",
                    "market", "filled", "", "API", "close",
                    95000.0, 0.25, 0.25, 3.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyString(), eq("SIGNAL_CLOSE"), anyLong());
        }

        @Test
        @DisplayName("入場 (tradeSide=open, orderType=limit) → recordLimitEntryFilled")
        void limitEntry() {
            Trade mockTrade = Trade.builder().symbol("BTCUSDT").side("LONG").build();
            when(tradeRecordService.recordLimitEntryFilled(anyString(), anyString(),
                    anyDouble(), anyDouble(), anyDouble(), anyLong()))
                    .thenReturn(mockTrade);

            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-005", "", "buy",
                    "limit", "filled", "", "API", "open",
                    95000.0, 0.25, 0.25, 3.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordLimitEntryFilled(
                    eq("BTCUSDT"), eq("bg-005"), eq(95000.0), eq(0.25), eq(3.0), eq(1700000000000L));
            assertThat(lastTitle).isEqualTo("✅ 限價入場成交");
        }

        @Test
        @DisplayName("非 filled 狀態 → 不處理")
        void notFilled() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-006", "", "buy",
                    "limit", "new", "", "API", "open",
                    95000.0, 0.25, 0.0, 0.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verifyNoInteractions(tradeRecordService);
        }

        @Test
        @DisplayName("強平 (enterPointSource=SYS) → LIQUIDATION")
        void liquidation() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-007", "", "sell",
                    "market", "filled", "", "SYS", "close",
                    91000.0, 0.25, 0.25, 5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            verify(tradeRecordService).recordOrderEvent(eq("BTCUSDT"), eq("LIQUIDATION"), isNull(), anyString());
            verify(tradeRecordService).markTradeClosedByLiquidation("BTCUSDT");
            assertThat(lastTitle).contains("強制平倉");
        }

        @Test
        @DisplayName("通知包含交易所名稱 BITGET")
        void notificationContainsExchange() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-008", "", "sell",
                    "market", "filled", "pos_loss", "API", "close",
                    93000.0, 0.25, 0.25, 5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            assertThat(lastMessage).contains("BITGET");
        }

        @Test
        @DisplayName("fee 負值 → 取絕對值")
        void negativeFee() {
            JsonObject data = buildBitgetOrder("BTCUSDT", "bg-009", "", "sell",
                    "market", "filled", "pos_loss", "API", "close",
                    93000.0, 0.25, 0.25, -5.0, 1700000000000L);

            handler.handleBitgetOrder(data);

            // fee 取絕對值 = 5.0
            verify(tradeRecordService).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(), eq(5.0),
                    anyDouble(), anyString(), anyString(), anyLong());
        }

        private JsonObject buildBitgetOrder(String symbol, String orderId, String clientOid,
                                              String side, String orderType, String status,
                                              String planType, String enterPointSource,
                                              String tradeSide, double priceAvg, double size,
                                              double filledQty, double fee, long uTime) {
            JsonObject data = new JsonObject();
            data.addProperty("symbol", symbol);
            data.addProperty("orderId", orderId);
            data.addProperty("clientOid", clientOid);
            data.addProperty("side", side);
            data.addProperty("orderType", orderType);
            data.addProperty("status", status);
            data.addProperty("planType", planType);
            data.addProperty("enterPointSource", enterPointSource);
            data.addProperty("tradeSide", tradeSide);
            data.addProperty("priceAvg", priceAvg);
            data.addProperty("size", size);
            data.addProperty("filledQty", filledQty);
            data.addProperty("fee", fee);
            data.addProperty("uTime", uTime);
            return data;
        }
    }

    // ==================== Bitget Position 事件 ====================

    @Nested
    @DisplayName("Bitget Position 事件 — handleBitgetPosition")
    class BitgetPosition {

        @Test
        @DisplayName("持倉資料 → 僅 log，不呼叫 tradeRecordService")
        void positionLogOnly() {
            OrderEventHandler handler = new OrderEventHandler(
                    tradeRecordService, symbolLockRegistry, notificationSender, null, gson, "", "BITGET");

            JsonObject data = new JsonObject();
            data.addProperty("symbol", "BTCUSDT");
            data.addProperty("holdSide", "long");
            data.addProperty("total", 0.25);
            data.addProperty("stopLossTriggerPrice", "93000.0");
            data.addProperty("stopSurplusTriggerPrice", "100000.0");

            handler.handleBitgetPosition(data);

            verifyNoInteractions(tradeRecordService);
        }
    }
}
