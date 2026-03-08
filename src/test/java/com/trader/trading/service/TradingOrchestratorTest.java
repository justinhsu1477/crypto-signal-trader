package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.model.TradeContext;
import com.trader.trading.validation.TradeSignalValidator;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradingOrchestrator 單元測試 — 交易所無關業務邏輯
 *
 * 測試策略：
 * - mock ExchangeAdapter（不依賴任何特定交易所）
 * - mock 所有 Service 依賴
 * - 驗證核心業務邏輯正確性
 *
 * 覆蓋範圍：
 * 1. executeSignal: 入場/DCA/白名單/熔斷/Fail-Safe/重複訊號
 * 2. executeClose: 全倉/部分平倉/SL重掛/TP重掛/無持倉
 * 3. executeMoveSL: 移動止損/成本保護/TP更新
 * 4. Symbol fallback
 * 5. 廣播 context
 */
class TradingOrchestratorTest {

    private ExchangeAdapter mockAdapter;
    private TradeRecordService mockTradeRecord;
    private SignalDeduplicationService mockDedup;
    private NotificationService mockNotification;
    private TradeConfigResolver mockTradeConfigResolver;
    private StartOfDayBalanceCache mockSodCache;
    private MultiUserConfig multiUserConfig;
    private TradingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        mockAdapter = mock(ExchangeAdapter.class);
        mockTradeRecord = mock(TradeRecordService.class);
        mockDedup = mock(SignalDeduplicationService.class);
        mockNotification = mock(NotificationService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);
        mockSodCache = mock(StartOfDayBalanceCache.class);
        multiUserConfig = new MultiUserConfig();

        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);
        when(mockSodCache.getOrCompute(any(), any())).thenReturn(10000.0);
        when(mockTradeRecord.getTodayRealizedLoss(anyString())).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
        when(mockDedup.isUserDuplicate(any(), any())).thenReturn(false);
        when(mockDedup.generateHash(any())).thenReturn("testhash");
        when(mockTradeRecord.getActiveUserId()).thenReturn("default-user");

        orchestrator = new TradingOrchestrator(
                mockTradeRecord, mockDedup, mockNotification,
                multiUserConfig, new ObjectMapper(),
                new SymbolLockRegistry(), mockTradeConfigResolver,
                mockSodCache, new TradeSignalValidator(), null);
    }

    @AfterEach
    void tearDown() {
        // removed: TradingOrchestrator.clearBroadcastContext()
    }

    // ==================== 共用 helper ====================

    private void setupEntryMocks(double balance, double position, double markPrice) {
        when(mockAdapter.getAvailableBalance()).thenReturn(balance);
        when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(position);
        when(mockAdapter.getMarkPrice(anyString())).thenReturn(markPrice);
        when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
        when(mockAdapter.getActivePositionCount()).thenReturn(position != 0 ? 1 : 0);
        when(mockAdapter.formatQuantity(anyString(), anyDouble())).thenAnswer(inv -> String.format("%.4f", (double) inv.getArgument(1)));
        when(mockAdapter.formatPrice(anyDouble())).thenAnswer(inv -> String.format("%.2f", (double) inv.getArgument(0)));
    }

    private OrderResult successOrder(String id, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(id).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    private TradeSignal buildEntrySignal(TradeSignal.Side side, double entry, double sl) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .side(side)
                .entryPriceLow(entry)
                .stopLoss(sl)
                .signalType(TradeSignal.SignalType.ENTRY)
                .build();
    }

    private TradeSignal buildCloseSignal(double ratio) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .signalType(TradeSignal.SignalType.CLOSE)
                .closeRatio(ratio)
                .build();
    }

    private TradeSignal buildMoveSLSignal(Double newSl, Double newTp) {
        var builder = TradeSignal.builder()
                .symbol("BTCUSDT")
                .signalType(TradeSignal.SignalType.MOVE_SL);
        if (newSl != null) builder.newStopLoss(newSl);
        if (newTp != null) builder.newTakeProfit(newTp);
        return builder.build();
    }

    // ==================== executeSignal ====================

    @Nested
    @DisplayName("executeSignal — 入場流程")
    class ExecuteSignalTests {

        @Test
        @DisplayName("做多入場成功 — 含 SL")
        void longEntrySuccess() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any(), anyString());
        }

        @Test
        @DisplayName("做空入場成功")
        void shortEntrySuccess() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "SELL", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "BUY", 97000, 0.01);

            when(mockAdapter.placeLimitOrder(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.SHORT, 95000, 97000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).isSuccess()).isTrue();
            // 做空: entry=SELL, SL=BUY
            verify(mockAdapter).placeLimitOrder(eq("BTCUSDT"), eq("SELL"), anyDouble(), anyDouble());
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("BUY"), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("入場含 TP — TP 成功")
        void entryWithTakeProfit() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.01);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .takeProfits(List.of(100000.0))
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(3);  // entry + SL + TP
            assertThat(results.get(2).isSuccess()).isTrue();
            verify(mockAdapter).setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("白名單拒絕 — 不在允許清單的交易對")
        void symbolNotInWhitelist() {
            setupEntryMocks(1000, 0, 5.0);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 5.0, 4.5);
            signal.setSymbol("DOGEUSDT");

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("白名單");
            // 不應呼叫任何下單方法
            verify(mockAdapter, never()).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("每日虧損熔斷 — 虧損超過上限")
        void dailyLossCircuitBreaker() {
            setupEntryMocks(1000, 0, 95000);

            when(mockTradeRecord.getTodayRealizedLoss(anyString())).thenReturn(-3000.0);
            when(mockSodCache.getOrCompute(any(), any())).thenReturn(1000.0);

            // 使用有 dailyLossPercent 的 config，讓 maxDailyLoss = 200 (1000 * 0.20)
            EffectiveTradeConfig strictConfig = new EffectiveTradeConfig(
                    0.20, 50000, 200, 0.20, 200, 3, 2.0, 20,
                    List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
            );
            when(mockTradeConfigResolver.resolve(any())).thenReturn(strictConfig);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("每日虧損");
            // 應發送熔斷通知
            verify(mockNotification).sendNotification(
                    eq("🚨 每日虧損熔斷"), anyString(), eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("重複訊號拒絕")
        void duplicateSignalRejected() {
            setupEntryMocks(1000, 0, 95000);

            when(mockDedup.isUserDuplicate(any(), any())).thenReturn(true);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("重複訊號");
        }

        @Test
        @DisplayName("已有持倉但非 DCA → 拒絕")
        void existingPositionWithoutDcaRejected() {
            setupEntryMocks(1000, 0.5, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("已有持倉");
        }

        @Test
        @DisplayName("入場缺少止損 → 拒絕")
        void entryWithoutStopLossRejected() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(0)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("stop_loss");
        }

        @Test
        @DisplayName("訊號驗證失敗 — symbol 為空")
        void signalValidationFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("驗證失敗");
        }

        @Test
        @DisplayName("價格偏離超過 10% → 拒絕")
        void priceDeviationExceeded() {
            setupEntryMocks(1000, 0, 50000);  // markPrice=50000 遠離 entry=95000

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("偏離市價");
        }
    }

    // ==================== DCA ====================

    @Nested
    @DisplayName("executeSignal — DCA 補倉")
    class DcaTests {

        @Test
        @DisplayName("DCA 成功 — 有持倉 + 未超上限")
        void dcaSuccess() {
            setupEntryMocks(1000, 0.5, 95000);  // 已有 0.5 BTC 多倉

            when(mockTradeRecord.getDcaCount(eq("BTCUSDT"), anyString())).thenReturn(1);
            when(mockTradeRecord.findOpenTrade(eq("BTCUSDT"), anyString())).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));

            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // DCA 應取消舊 SL/TP
            verify(mockAdapter).cancelSLTPOrders("BTCUSDT");
            verify(mockTradeRecord).recordDcaEntry(eq("BTCUSDT"), any(), any(), anyDouble(), anyString());
        }

        @Test
        @DisplayName("DCA 超過上限 → 拒絕")
        void dcaExceedsMax() {
            setupEntryMocks(1000, 0.5, 95000);

            when(mockTradeRecord.getDcaCount(eq("BTCUSDT"), anyString())).thenReturn(3);  // max=3

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("DCA");
        }

        @Test
        @DisplayName("DCA 無持倉 → 拒絕")
        void dcaNoPosition() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("沒有持倉");
        }
    }

    // ==================== Fail-Safe ====================

    @Nested
    @DisplayName("executeSignal — Fail-Safe 機制")
    class FailSafeTests {

        @Test
        @DisplayName("SL 失敗 → 取消入場單")
        void slFailCancelsEntry() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 入場應標記為失敗
            assertThat(results.get(0).isSuccess()).isFalse();
            // 應嘗試取消入場單
            verify(mockAdapter).cancelOrder(eq("BTCUSDT"), eq("E1"));
            // 應發送 Fail-Safe 通知
            verify(mockNotification).sendNotification(
                    contains("Fail-Safe"), anyString(), eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("SL 失敗 + 取消失敗 → 市價平倉")
        void slFailCancelFailMarketClose() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));
            doThrow(new RuntimeException("cancel failed")).when(mockAdapter).cancelOrder(anyString(), anyString());

            OrderResult marketClose = successOrder("MC1", "SELL", 95000, 0.01);
            when(mockAdapter.placeMarketOrder(anyString(), anyString(), anyDouble())).thenReturn(marketClose);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 應嘗試市價平倉
            verify(mockAdapter).placeMarketOrder(anyString(), anyString(), anyDouble());
        }
    }

    // ==================== executeClose ====================

    @Nested
    @DisplayName("executeClose — 平倉流程")
    class ExecuteCloseTests {

        @Test
        @DisplayName("全倉平倉成功 — MARKET 單")
        void fullCloseSuccess() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            when(mockAdapter.placeMarketOrder(anyString(), eq("SELL"), anyDouble())).thenReturn(closeOrder);

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(anyString(), any(), anyString(), anyString());
            // 全倉平倉用市價單
            verify(mockAdapter).placeMarketOrder(anyString(), eq("SELL"), anyDouble());
        }

        @Test
        @DisplayName("部分平倉 50% + SL 重掛")
        void partialCloseWithSLRehang() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{93000, 100000});

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // SL 應重掛到剩餘數量 0.5
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
            // TP 也重掛
            verify(mockAdapter).setTakeProfit(eq("BTCUSDT"), eq("SELL"), eq(100000.0), eq(0.5));
        }

        @Test
        @DisplayName("無持倉 + 有未成交委託 → 撤銷掛單")
        void closeNoPositionWithPendingOrders() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(true);

            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(List.of());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            verify(mockAdapter).cancelAllOrders(anyString());
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(0).getErrorMessage()).contains("未成交委託已撤銷");
        }

        @Test
        @DisplayName("無持倉也無掛單 → FAIL")
        void closeNoPositionNoPendingOrders() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);

            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(List.of());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("無持倉也無掛單");
        }

        @Test
        @DisplayName("廣播 context 下無持倉 → 不發 notifyGlobal")
        void closeNoPositionBroadcastContextSkipsNotify() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(List.of());

            TradeSignal signal = buildCloseSignal(1.0);
            orchestrator.executeClose(signal, mockAdapter, TradeContext.forBroadcast("test-user", "Test User"));

            // 廣播 context 下不應發通知
            verify(mockNotification, never()).sendNotification(contains("無持倉"), anyString(), anyInt());
        }

        @Test
        @DisplayName("空倉部分平倉 — 方向正確 (BUY)")
        void shortPartialCloseDirection() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(-1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{97000, 90000});

            OrderResult closeOrder = successOrder("C1", "BUY", 94000, 0.5);
            OrderResult slOrder = successOrder("SL1", "BUY", 97000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "BUY", 90000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            // 空倉平倉方向 = BUY
            verify(mockAdapter).placeLimitOrder(eq("BTCUSDT"), eq("BUY"), anyDouble(), anyDouble());
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("BUY"), eq(97000.0), eq(0.5));
        }
    }

    // ==================== executeMoveSL ====================

    @Nested
    @DisplayName("executeMoveSL — 移動止損")
    class ExecuteMoveSLTests {

        @Test
        @DisplayName("移動 SL 到新價格 — 成功")
        void moveSLSuccess() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockTradeRecord.findOpenTrade(eq("BTCUSDT"), anyString())).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildMoveSLSignal(94500.0, null);
            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(94500.0), eq(0.5));
            verify(mockTradeRecord).recordMoveSL(eq("BTCUSDT"), any(), anyDouble(), eq(94500.0), anyString());
        }

        @Test
        @DisplayName("成本保護 — newSL=null 使用開倉價")
        void costProtectionUsesEntryPrice() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockTradeRecord.findOpenTrade(eq("BTCUSDT"), anyString())).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));
            when(mockTradeRecord.getEntryPrice(eq("BTCUSDT"), anyString())).thenReturn(95000.0);

            OrderResult slOrder = successOrder("SL1", "SELL", 95000, 0.5);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            // newSL=null → 成本保護，用開倉價
            // 需要 newTp 才能通過 validator
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newTakeProfit(100000.0)
                    .build();

            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            // 應用入場價 95000 作為 SL
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), anyString(), eq(95000.0), anyDouble());
        }

        @Test
        @DisplayName("移動 SL + 更新 TP")
        void moveSLWithNewTP() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockTradeRecord.findOpenTrade(eq("BTCUSDT"), anyString())).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = buildMoveSLSignal(94500.0, 100000.0);
            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).hasSize(2);
            verify(mockAdapter).setTakeProfit(eq("BTCUSDT"), anyString(), eq(100000.0), anyDouble());
        }

        @Test
        @DisplayName("無持倉 → FAIL")
        void moveSLNoPosition() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(List.of());

            TradeSignal signal = buildMoveSLSignal(94500.0, null);
            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("TP 失敗 — 發送黃色告警")
        void tpFailureSendsYellowAlert() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.25);
            when(mockTradeRecord.findOpenTrade(anyString(), anyString())).thenReturn(Optional.empty());

            OrderResult slOk = successOrder("SL1", "SELL", 94000, 0.25);
            OrderResult tpFail = OrderResult.fail("TP error");
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOk);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpFail);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(94000.0)
                    .takeProfits(List.of(98000.0))
                    .build();

            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // SL 成功，TP 失敗
            assertThat(results).hasSize(2);
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).isSuccess()).isFalse();

            // 應發送 TP 失敗黃色告警
            verify(mockNotification).sendNotification(
                    contains("止盈單失敗"), contains("請手動設定 TP"),
                    eq(NotificationService.COLOR_YELLOW));
        }
    }

    // ==================== Symbol Fallback ====================

    @Nested
    @DisplayName("Symbol Fallback")
    class SymbolFallbackTests {

        @Test
        @DisplayName("CLOSE — 訊號 symbol 無持倉，DB 有唯一 OPEN trade → fallback")
        void closeSymbolFallback() {
            // 第一次查 BTCUSDT 無持倉
            when(mockAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);
            // DB 有唯一 OPEN trade: ETHUSDT
            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(
                    List.of(Trade.builder().symbol("ETHUSDT").build()));
            // 第二次查 ETHUSDT 有持倉
            when(mockAdapter.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.5);
            when(mockAdapter.getMarkPrice("ETHUSDT")).thenReturn(3000.0);

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("ETHUSDT")
                    .side("SELL").type("MARKET").price(3000).quantity(0.5)
                    .build();
            when(mockAdapter.placeMarketOrder(eq("ETHUSDT"), eq("SELL"), anyDouble())).thenReturn(closeOrder);

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 應使用 fallback 的 ETHUSDT
            verify(mockAdapter).placeMarketOrder(eq("ETHUSDT"), eq("SELL"), anyDouble());
            // 應發送 symbol 修正通知
            verify(mockNotification).sendNotification(
                    contains("Symbol 自動修正"), anyString(), eq(NotificationService.COLOR_BLUE));
        }

        @Test
        @DisplayName("MOVE_SL — Symbol Fallback 生效")
        void moveSLSymbolFallback() {
            when(mockAdapter.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);
            when(mockTradeRecord.findAllOpenTrades(anyString())).thenReturn(
                    List.of(Trade.builder().symbol("ETHUSDT").build()));
            when(mockAdapter.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.5);
            when(mockTradeRecord.findOpenTrade(eq("ETHUSDT"), anyString())).thenReturn(
                    Optional.of(Trade.builder().stopLoss(2800.0).build()));

            OrderResult slOrder = OrderResult.builder()
                    .success(true).orderId("SL1").symbol("ETHUSDT")
                    .side("SELL").type("STOP_MARKET").price(2900).quantity(0.5)
                    .build();
            when(mockAdapter.setStopLoss(eq("ETHUSDT"), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildMoveSLSignal(2900.0, null);
            List<OrderResult> results = orchestrator.executeMoveSL(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockAdapter).setStopLoss(eq("ETHUSDT"), anyString(), eq(2900.0), anyDouble());
        }
    }

    // ==================== 通知路由 ====================

    @Nested
    @DisplayName("notifyGlobal — 通知路由")
    class NotifyGlobalTests {

        @Test
        @DisplayName("單用戶模式 — 只發 global")
        void singleUserModeGlobalOnly() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 單用戶：只有 sendNotification
            verify(mockNotification, atLeastOnce()).sendNotification(anyString(), anyString(), anyInt());
            verify(mockNotification, never()).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
            verify(mockNotification, never()).sendNotificationToAdmins(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("多用戶模式 — 發 per-user + admin")
        void multiUserModeTwoWay() {
            multiUserConfig.setEnabled(true);
            TradeRecordService.setCurrentUserId("user-test");
            TradeRecordService.setCurrentUserDisplayName("Test User (test@example.com)");
            when(mockTradeRecord.getActiveUserId()).thenReturn("user-test");

            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 多用戶模式：不發 sendNotification，改發 per-user + admin
            verify(mockNotification, never()).sendNotification(anyString(), anyString(), anyInt());
            verify(mockNotification, atLeastOnce()).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
            verify(mockNotification, atLeastOnce()).sendNotificationToAdmins(anyString(), anyString(), anyString(), anyInt());

            // 清理 ThreadLocal
            TradeRecordService.clearCurrentUserId();
        }
    }

    // ==================== TP 失敗告警（ENTRY 流程） ====================

    @Nested
    @DisplayName("TP 失敗告警")
    class TpFailureAlertTests {

        @Test
        @DisplayName("ENTRY 流程 — TP 失敗發送黃色告警")
        void entryTpFailureSendsYellowAlert() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOk = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOk = successOrder("SL1", "SELL", 93000, 0.01);
            OrderResult tpFail = OrderResult.fail("TP order rejected");

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOk);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOk);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpFail);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .takeProfits(List.of(97000.0))
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 入場和止損成功
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).isSuccess()).isTrue();

            // 應發送 TP 失敗黃色告警
            verify(mockNotification).sendNotification(
                    contains("止盈單失敗"),
                    contains("請手動設定 TP"),
                    eq(NotificationService.COLOR_YELLOW));
        }
    }

    // ==================== 部分平倉 SL 優先級 ====================

    @Nested
    @DisplayName("部分平倉 SL 優先級")
    class PartialCloseSLPriorityTests {

        @Test
        @DisplayName("有新 SL → 用新 SL 重掛")
        void useNewSLFromSignal() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{93000, 100000});

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .newStopLoss(94500.0)
                    .build();

            orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 驗證用新 SL 94500
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(94500.0), anyDouble());
        }

        @Test
        @DisplayName("無 SL 資訊 → SL_REHUNG_FAILED 記錄")
        void noSLInfoRecordsFailure() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{0, 0});
            when(mockTradeRecord.getEntryPrice(eq("BTCUSDT"), anyString())).thenReturn(null);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);

            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = orchestrator.executeClose(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 應記錄 SL_REHUNG_FAILED 事件
            verify(mockTradeRecord).recordOrderEvent(eq("BTCUSDT"), eq("SL_REHUNG_FAILED"), isNull(), anyString(), anyString());
            // 結果中應有一個失敗的 SL 結果
            assertThat(results.stream().anyMatch(r -> !r.isSuccess())).isTrue();
        }
    }

    // ==================== 通知例外不影響交易 ====================

    @Nested
    @DisplayName("交易安全迴歸")
    class TradingSafetyTests {

        @Test
        @DisplayName("通知服務拋例外 — 不影響進場流程")
        void notificationExceptionDoesNotBreakEntry() {
            setupEntryMocks(1000, 0, 95000);

            doThrow(new RuntimeException("Discord webhook timeout"))
                    .when(mockNotification).sendNotification(anyString(), anyString(), anyInt());

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = orchestrator.executeSignal(signal, mockAdapter, TradeContext.fromRequest("test-user"));

            // 進場仍應成功
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any(), anyString());
        }
    }
}
