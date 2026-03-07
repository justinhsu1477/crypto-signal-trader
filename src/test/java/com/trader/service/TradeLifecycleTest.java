package com.trader.service;

import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.exchange.binance.BinanceAdapter;
import com.trader.trading.service.*;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.validation.TradeSignalValidator;
import com.trader.user.service.UserApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 完整交易生命週期測試
 *
 * 測試複合流程（多步驟組合）：
 * 1. ENTRY → MOVE_SL → CLOSE（做多完整生命週期）
 * 2. ENTRY → DCA → 部分平倉 → 全平（DCA 生命週期）
 * 3. ENTRY → SL 失敗 → Fail-Safe → 市價平倉失敗（最壞情況）
 * 4. DCA 方向衝突檢查
 * 5. 入場後重複訊號被擋
 * 6. 已有未成交掛單被擋
 */
class TradeLifecycleTest {

    private TradeRecordService mockTradeRecord;
    private SignalDeduplicationService mockDedup;
    private NotificationService mockWebhook;
    private TradeConfigResolver mockTradeConfigResolver;
    private BinanceAdapter mockAdapter;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(
                50000, 2000, 0.80, 0,
                true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(NotificationService.class);
        UserApiKeyService mockApiKey = mock(UserApiKeyService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);
        mockAdapter = mock(BinanceAdapter.class);

        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        TradingOrchestrator orchestrator = new TradingOrchestrator(
                mockTradeRecord, mockDedup, mockWebhook,
                new MultiUserConfig(), new ObjectMapper(), new SymbolLockRegistry(),
                mockTradeConfigResolver, mock(StartOfDayBalanceCache.class),
                new TradeSignalValidator(), null);

        service = new BinanceFuturesService(
                mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                new MultiUserConfig(), new SymbolLockRegistry(), mockApiKey,
                mockTradeConfigResolver);

        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
        when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);
    }

    private void setupEntryMocks(double balance, double position, double markPrice) {
        when(mockAdapter.getAvailableBalance()).thenReturn(balance);
        when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(position);
        when(mockAdapter.getActivePositionCount()).thenReturn(0);
        when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
        when(mockAdapter.getMarkPrice(anyString())).thenReturn(markPrice);
    }

    private OrderResult ok(String id, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(id).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    // ==================== 完整生命週期 ====================

    @Nested
    @DisplayName("完整交易生命週期")
    class FullLifecycle {

        @Test
        @DisplayName("做多：ENTRY → MOVE_SL → 全平 CLOSE")
        void longEntryMoveSLClose() {
            // === Step 1: ENTRY ===
            setupEntryMocks(10000, 0, 95000);

            OrderResult entry = ok("E1", "BUY", 95000, 0.1);
            OrderResult sl = ok("SL1", "SELL", 93000, 0.1);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(sl);

            TradeSignal entrySignal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> entryResults = service.executeSignal(entrySignal);
            assertThat(entryResults.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any());

            // === Step 2: MOVE_SL ===
            when(mockAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.1);

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult newSl = ok("SL2", "SELL", 95500, 0.1);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), eq(95500.0), anyDouble())).thenReturn(newSl);

            TradeSignal moveSLSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(95500.0)
                    .build();

            List<OrderResult> moveSLResults = service.executeMoveSL(moveSLSignal);
            assertThat(moveSLResults.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordMoveSL(eq("BTCUSDT"), any(), eq(93000.0), eq(95500.0));

            // === Step 3: FULL CLOSE ===
            when(mockAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.1);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(98000.0);

            OrderResult closeOrder = ok("C1", "SELL", 98000, 0.1);
            when(mockAdapter.placeMarketOrder(eq("BTCUSDT"), eq("SELL"), anyDouble())).thenReturn(closeOrder);

            TradeSignal closeSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();

            List<OrderResult> closeResults = service.executeClose(closeSignal);
            assertThat(closeResults.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(eq("BTCUSDT"), any(), eq("SIGNAL_CLOSE"));
        }

        @Test
        @DisplayName("做空：ENTRY → MOVE_SL → 全平 CLOSE")
        void shortEntryMoveSLClose() {
            // === Step 1: ENTRY ===
            setupEntryMocks(10000, 0, 95000);

            OrderResult entry = ok("E1", "SELL", 95000, 0.1);
            OrderResult sl = ok("SL1", "BUY", 97000, 0.1);
            when(mockAdapter.placeLimitOrder(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(sl);

            TradeSignal entrySignal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.SHORT)
                    .entryPriceLow(95000).stopLoss(97000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> entryResults = service.executeSignal(entrySignal);
            assertThat(entryResults.get(0).isSuccess()).isTrue();

            // === Step 2: FULL CLOSE ===
            when(mockAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(-0.1);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(93000.0);

            OrderResult closeOrder = ok("C1", "BUY", 93000, 0.1);
            when(mockAdapter.placeMarketOrder(eq("BTCUSDT"), eq("BUY"), anyDouble())).thenReturn(closeOrder);

            TradeSignal closeSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();

            List<OrderResult> closeResults = service.executeClose(closeSignal);
            assertThat(closeResults.get(0).isSuccess()).isTrue();
            // 做空平倉方向應為 BUY
            verify(mockAdapter).placeMarketOrder(eq("BTCUSDT"), eq("BUY"), anyDouble());
        }
    }

    // ==================== DCA 生命週期 ====================

    @Nested
    @DisplayName("DCA 生命週期")
    class DcaLifecycle {

        @Test
        @DisplayName("DCA 方向衝突 — 持多倉但 DCA 訊號為空 → 拒絕")
        void dcaDirectionConflict() {
            setupEntryMocks(10000, 0.5, 95000);  // 持有多倉 0.5 BTC

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").build()));

            TradeSignal dcaSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.SHORT)  // 反方向！
                    .entryPriceLow(93000)
                    .stopLoss(95000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = service.executeSignal(dcaSignal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("方向");
        }

        @Test
        @DisplayName("DCA 無帶 side → 自動推斷持倉方向")
        void dcaAutoInferDirection() {
            setupEntryMocks(10000, 0.5, 95000);  // 持有多倉

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(0);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));


            OrderResult dcaEntry = ok("DCA1", "BUY", 94000, 0.02);
            OrderResult dcaSl = ok("SL1", "SELL", 93000, 0.52);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(dcaEntry);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(dcaSl);

            TradeSignal dcaSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    // 不帶 side → 應該自動推斷為 LONG
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = service.executeSignal(dcaSignal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 應該用 BUY（LONG 方向）
            verify(mockAdapter).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
        }
    }

    // ==================== Fail-Safe 最壞情況 ====================

    @Nested
    @DisplayName("Fail-Safe 升級鏈")
    class FailSafeEscalation {

        @Test
        @DisplayName("SL 失敗 + 取消成功 → 不嘗試市價平倉")
        void slFailCancelSuccess() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entry = ok("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            service.executeSignal(signal);

            // 取消成功 → 不需要市價平倉
            verify(mockAdapter).cancelOrder(eq("BTCUSDT"), eq("12345"));
            verify(mockAdapter, never()).placeMarketOrder(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("SL 失敗 + 取消失敗 + 市價平倉成功 → 記錄 FAIL_SAFE_CLOSE")
        void slFailCancelFailMarketCloseSuccess() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entry = ok("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));
            doThrow(new RuntimeException("cancel failed")).when(mockAdapter)
                    .cancelOrder(anyString(), anyString());

            OrderResult marketClose = ok("MC1", "SELL", 95000, 0.01);
            when(mockAdapter.placeMarketOrder(anyString(), eq("SELL"), anyDouble())).thenReturn(marketClose);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            service.executeSignal(signal);

            verify(mockAdapter).placeMarketOrder(anyString(), eq("SELL"), anyDouble());
            verify(mockTradeRecord).recordOrderEvent(eq("BTCUSDT"), eq("FAIL_SAFE_CLOSE"), any(), anyString());
        }

        @Test
        @DisplayName("SL 失敗 + 取消失敗 + 市價平倉也失敗 → CRITICAL 通知 + 記錄")
        void allFailSafeMeasuresFail() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entry = ok("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));
            doThrow(new RuntimeException("cancel failed")).when(mockAdapter)
                    .cancelOrder(anyString(), anyString());
            when(mockAdapter.placeMarketOrder(anyString(), anyString(), anyDouble()))
                    .thenReturn(OrderResult.fail("market close failed"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 所有失敗 → entryOrder 標記為 failed
            assertThat(results.get(0).isSuccess()).isFalse();

            // 應發送 CRITICAL 通知
            verify(mockWebhook).sendNotification(contains("全部失敗"), anyString(), anyInt());

            // 記錄兩次 fail-safe（一次 SL 失敗，一次全部失敗）
            verify(mockTradeRecord, atLeast(2)).recordFailSafe(eq("BTCUSDT"), anyString());
        }
    }

    // ==================== 防護檢查 ====================

    @Nested
    @DisplayName("防護邏輯")
    class GuardChecks {

        @Test
        @DisplayName("有未成交掛單 → 拒絕新入場（防止重複）")
        void rejectWhenOpenEntryOrders() {
            setupEntryMocks(10000, 0, 95000);
            when(mockAdapter.hasOpenEntryOrders("BTCUSDT")).thenReturn(true);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("掛單");
        }

        @Test
        @DisplayName("DCA 跳過掛單檢查（允許多張 LIMIT 同時存在）")
        void dcaSkipsOpenOrderCheck() {
            setupEntryMocks(10000, 0.5, 95000);
            when(mockAdapter.hasOpenEntryOrders("BTCUSDT")).thenReturn(true);

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(0);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));


            OrderResult dcaEntry = ok("DCA1", "BUY", 94000, 0.02);
            OrderResult dcaSl = ok("SL1", "SELL", 93000, 0.52);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(dcaEntry);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(dcaSl);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(94000).stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // DCA 應該通過，不被掛單檢查擋住
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("API 查詢餘額失敗 → 拒絕交易（RuntimeException 被攔截）")
        void rejectWhenBalanceQueryFails() {
            when(mockAdapter.getAvailableBalance()).thenThrow(new RuntimeException("API timeout"));
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("前置檢查失敗");
        }

        @Test
        @DisplayName("持倉查詢失敗 → 拒絕交易")
        void rejectWhenPositionQueryFails() {
            when(mockAdapter.getAvailableBalance()).thenReturn(10000.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenThrow(new RuntimeException("查詢持倉失敗"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("入場帶 TP — TP 失敗不影響入場和 SL")
        void tpFailureDoesNotBlockEntry() {
            setupEntryMocks(10000, 0, 95000);

            OrderResult entry = ok("E1", "BUY", 95000, 0.01);
            OrderResult sl = ok("SL1", "SELL", 93000, 0.01);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entry);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(sl);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("TP placement failed"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(93000)
                    .takeProfits(List.of(100000.0))
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 入場和 SL 應該成功
            assertThat(results.get(0).isSuccess()).isTrue();
            // TP 失敗應記錄事件
            verify(mockTradeRecord).recordOrderEvent(eq("BTCUSDT"), eq("TP_FAILED"), any(), isNull());
            // 應發 Discord 通知 TP 失敗
            verify(mockWebhook).sendNotification(contains("止盈單失敗"), anyString(), anyInt());
        }
    }
}
