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
 * BinanceFuturesService 核心交易邏輯測試
 *
 * 策略：mock BinanceAdapter + 真實 TradingOrchestrator
 * 測試重點：進場流程、DCA、平倉、移動止損、風控
 */
class BinanceFuturesServiceTest {

    private RiskConfig riskConfig;
    private TradeRecordService mockTradeRecord;
    private SignalDeduplicationService mockDedup;
    private NotificationService mockWebhook;
    private UserApiKeyService mockUserApiKeyService;
    private TradeConfigResolver mockTradeConfigResolver;
    private MultiUserConfig multiUserConfig;
    private BinanceAdapter mockAdapter;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        riskConfig = new RiskConfig(
                50000, 2000, 0.80, 0,
                true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(NotificationService.class);
        mockUserApiKeyService = mock(UserApiKeyService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false（單用戶）
        mockAdapter = mock(BinanceAdapter.class);

        // mock TradeConfigResolver — 回傳與全局 RiskConfig 一致的 EffectiveTradeConfig
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        TradingOrchestrator orchestrator = new TradingOrchestrator(
                mockTradeRecord, mockDedup, mockWebhook, multiUserConfig,
                new ObjectMapper(), new SymbolLockRegistry(),
                mockTradeConfigResolver, mock(StartOfDayBalanceCache.class),
                new TradeSignalValidator(), null);

        service = new BinanceFuturesService(
                mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                multiUserConfig, new SymbolLockRegistry(), mockUserApiKeyService,
                mockTradeConfigResolver);

        // 通用 mock — 大部分測試需要的基礎環境
        when(mockTradeRecord.getActiveUserId()).thenReturn("test-user");
        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
        when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

        // 設定 displayName ThreadLocal（notifyGlobal 會讀取）
        TradeRecordService.setCurrentUserDisplayName("Test User (test@example.com)");
    }

    @AfterEach
    void tearDown() {
        TradeRecordService.clearCurrentUserDisplayName();
        TradeRecordService.clearCurrentUserId();
    }

    // ==================== Helper ====================

    private TradeSignal buildEntrySignal(TradeSignal.Side side, double entry, double sl) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .side(side)
                .entryPriceLow(entry)
                .stopLoss(sl)
                .signalType(TradeSignal.SignalType.ENTRY)
                .build();
    }

    private TradeSignal buildDcaSignal(double entry, double sl) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .entryPriceLow(entry)
                .stopLoss(sl)
                .signalType(TradeSignal.SignalType.ENTRY)
                .isDca(true)
                .build();
    }

    private TradeSignal buildCloseSignal(double ratio) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .signalType(TradeSignal.SignalType.CLOSE)
                .closeRatio(ratio)
                .build();
    }

    private TradeSignal buildMoveSLSignal(Double newSL, Double newTP) {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .signalType(TradeSignal.SignalType.MOVE_SL)
                .newStopLoss(newSL)
                .newTakeProfit(newTP)
                .build();
    }

    private OrderResult successOrder(String orderId, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(orderId).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    /**
     * 設定正常進場前的通用 mock（餘額、持倉、掛單、標記價格）
     */
    private void setupEntryMocks(double balance, double currentPosition, double markPrice) {
        when(mockAdapter.getAvailableBalance()).thenReturn(balance);
        when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(currentPosition);
        when(mockAdapter.getActivePositionCount()).thenReturn(0);
        when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
        when(mockAdapter.getMarkPrice(anyString())).thenReturn(markPrice);
    }

    // ==================== Entry Flow ====================

    @Nested
    @DisplayName("進場流程")
    class EntryFlow {

        @Test
        @DisplayName("做多入場成功 — 入場單 + SL 都成功")
        void longEntrySuccess() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any());
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
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("入場單失敗 → 回傳 fail + 記錄 ENTRY_FAILED 事件")
        void entryOrderFails() {
            setupEntryMocks(1000, 0, 95000);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("Insufficient margin"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            verify(mockTradeRecord).recordOrderEvent(eq("BTCUSDT"), eq("ENTRY_FAILED"), any(), any());
        }

        @Test
        @DisplayName("SL 下單失敗 → 觸發 fail-safe 取消入場單")
        void slFailsTriggerFailSafe() {
            setupEntryMocks(1000, 0, 95000);

            // orderId 必須是數字字串（fail-safe 用 Long.parseLong 解析）
            OrderResult entryOrder = successOrder("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL placement failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 應該嘗試取消入場單
            verify(mockAdapter).cancelOrder(eq("BTCUSDT"), eq("12345"));
            // 結果應標記為失敗（fail-safe 觸發）
            assertThat(results.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("幣種不在白名單 → 拒絕")
        void rejectSymbolNotAllowed() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("DOGEUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(0.15)
                    .stopLoss(0.14)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("DOGEUSDT");
        }

        @Test
        @DisplayName("重複訊號 → 拒絕")
        void rejectDuplicateSignal() {
            setupEntryMocks(1000, 0, 95000);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(true);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("重複");
        }

        @Test
        @DisplayName("已有持倉（非 DCA）→ 拒絕")
        void rejectWhenPositionExistsNotDca() {
            setupEntryMocks(1000, 0.5, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("做多止損高於入場價 → 拒絕")
        void rejectLongSLAboveEntry() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 96000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("止損");
        }

        @Test
        @DisplayName("做空止損低於入場價 → 拒絕")
        void rejectShortSLBelowEntry() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.SHORT, 95000, 94000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("止損");
        }

        @Test
        @DisplayName("價格偏差超過 10% → 拒絕")
        void rejectPriceDeviationTooHigh() {
            setupEntryMocks(1000, 0, 95000);

            // 入場價 80000，標記價 95000，偏差 ≈ 15.8%
            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 80000, 78000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("偏離");
        }

        @Test
        @DisplayName("有 TP 目標的進場 — 記錄 TP_PLACED 或 TP_FAILED")
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

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockAdapter).setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("入場缺少 SL → 拒絕")
        void rejectEntryWithoutSL() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(0)  // 沒有止損
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("stop_loss");
        }
    }

    // ==================== DCA Flow ====================

    @Nested
    @DisplayName("DCA 補倉流程")
    class DcaFlow {

        @Test
        @DisplayName("DCA 成功 — 有持倉 + 未超過上限")
        void dcaSuccessWithExistingPosition() {
            setupEntryMocks(1000, 0.5, 95000);  // 已有 0.5 BTC 多倉

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));

            // DCA 呼叫 cancelSLTPOrders → 內部呼叫 getOpenAlgoOrders


            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildDcaSignal(94000, 92000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("DCA 超過最大次數 → 拒絕")
        void dcaExceedsMaxLayers() {
            setupEntryMocks(1000, 0.5, 95000);

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(3);  // max = 3

            TradeSignal signal = buildDcaSignal(94000, 92000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("DCA");
        }

        @Test
        @DisplayName("DCA 無持倉 → 拒絕")
        void dcaWithNoPosition() {
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = buildDcaSignal(94000, 92000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("DCA 不帶新止損（stopLoss=0）→ 不被拒，使用 DB 現有 SL")
        void dcaWithoutNewStopLossUsesExistingSL() {
            setupEntryMocks(1000, 0.5, 95000);  // 已有 0.5 BTC 多倉

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));



            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            // DCA 不帶止損：stopLoss=0, newStopLoss=null（模擬 Controller 的行為）
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .entryPriceLow(94000)
                    .stopLoss(0)              // Controller 設為 0
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    // newStopLoss = null（不帶新止損）
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 不應被拒，應該成功
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 確認用 DB 的現有 SL (93000) 重掛
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), anyDouble());
        }
    }

    // ==================== Close Flow ====================

    @Nested
    @DisplayName("平倉流程")
    class CloseFlow {

        @Test
        @DisplayName("全倉平倉成功 — 使用 MARKET 單")
        void fullCloseSuccess() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            when(mockAdapter.placeMarketOrder(anyString(), eq("SELL"), anyDouble())).thenReturn(closeOrder);

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("部分平倉 50% — SL 重掛剩餘倉位")
        void partialCloseWithSLRehang() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{93000.0, 100000.0});

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            // 部分平倉後重掛 TP
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);
            // cancelSLTPOrders 內部需要 getOpenAlgoOrders


            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 應該重掛 SL
            verify(mockAdapter).setStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("無持倉但有未成交委託 → 撤銷掛單 → 返回 SUCCESS")
        void closeNoPositionWithPendingOrders() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(true);

            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            verify(mockAdapter).cancelAllOrders(anyString());
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(0).getErrorMessage()).contains("未成交委託已撤銷");
        }

        @Test
        @DisplayName("無持倉也無掛單 → 返回 FAIL 並忽略")
        void closeNoPositionNoPendingOrders() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);

            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            verify(mockAdapter).cancelAllOrders(anyString());
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("無持倉也無掛單");
        }
    }

    // ==================== Move SL ====================

    @Nested
    @DisplayName("移動止損流程")
    class MoveSLFlow {

        @Test
        @DisplayName("移動 SL 到新價格 — 成功")
        void moveSLSuccess() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            TradeSignal signal = buildMoveSLSignal(94500.0, null);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("成本保護 — newSL=null 使用入場價")
        void costProtectionUsesEntryPrice() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);

            when(mockTradeRecord.getEntryPrice("BTCUSDT")).thenReturn(95000.0);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 95000, 0.5);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildMoveSLSignal(null, null);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            // 應該用入場價 95000 而非 null
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), anyString(), eq(95000.0), anyDouble());
        }

        @Test
        @DisplayName("移動 SL + 更新 TP")
        void moveSLWithNewTP() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = buildMoveSLSignal(94500.0, 100000.0);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results.size()).isGreaterThanOrEqualTo(2);
            verify(mockAdapter).setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("無持倉 → 回傳 fail")
        void moveSLNoPosition() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);

            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildMoveSLSignal(94500.0, null);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
        }
    }

    // ==================== Fail-Safe ====================

    @Nested
    @DisplayName("Fail-Safe 機制")
    class FailSafe {

        @Test
        @DisplayName("SL 失敗 + 取消失敗 → 市價平倉")
        void slFailCancelFailMarketClose() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));
            doThrow(new RuntimeException("cancel failed")).when(mockAdapter).cancelOrder(anyString(), anyString());

            // 市價平倉
            OrderResult marketClose = successOrder("MC1", "SELL", 95000, 0.01);
            when(mockAdapter.placeMarketOrder(anyString(), anyString(), anyDouble())).thenReturn(marketClose);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 應該嘗試市價平倉
            verify(mockAdapter).placeMarketOrder(anyString(), anyString(), anyDouble());
        }
    }

    // ==================== 全局通知 userId ====================

    @Nested
    @DisplayName("notifyGlobal — 全局通知自動附加 userId")
    class NotifyGlobalTests {

        @Test
        @DisplayName("每日虧損熔斷通知 — 包含 userId")
        void circuitBreakerNotificationContainsUserId() {
            // 模擬今日虧損已達上限
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-3000.0);
            // SOD balance cache
            var sodCache = mock(StartOfDayBalanceCache.class);
            when(sodCache.getOrCompute(anyString(), any())).thenReturn(1000.0);

            // 用嚴格的 EffectiveTradeConfig，讓 maxDailyLoss = 200 (1000 * 0.20)
            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.20, 50000, 200, 0.20, 200, 3, 2.0, 20,
                    List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
            );
            when(mockTradeConfigResolver.resolve(any())).thenReturn(config);

            // 重建 orchestrator + service 以注入 sodCache
            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, multiUserConfig,
                    new ObjectMapper(), new SymbolLockRegistry(),
                    mockTradeConfigResolver, sodCache,
                    new TradeSignalValidator(), null);

            service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    multiUserConfig, new SymbolLockRegistry(), mockUserApiKeyService,
                    mockTradeConfigResolver);

            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // 驗證通知內容包含 displayName
            verify(mockWebhook).sendNotification(
                    eq("🚨 每日虧損熔斷"),
                    contains("用戶: Test User (test@example.com)"),
                    eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("SL 失敗 fail-safe 通知 — 包含 userId")
        void failSafeNotificationContainsUserId() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL placement failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // fail-safe 通知應包含 displayName
            verify(mockWebhook).sendNotification(
                    eq("🛑 Fail-Safe: 止損失敗，入場單已取消"),
                    contains("用戶: Test User (test@example.com)"),
                    eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("無持倉平倉通知 — 包含 userId")
        void closeNoPositionNotificationContainsUserId() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            service.executeClose(signal);

            // 無持倉平倉通知應包含 displayName（場景 B：無掛單）
            verify(mockWebhook).sendNotification(
                    contains("無持倉也無掛單"),
                    contains("用戶: Test User (test@example.com)"),
                    anyInt());
        }

        @Test
        @DisplayName("單用戶模式 — 只發 global，不發 per-user / admin")
        void singleUserModeOnlyGlobal() {
            // multiUserConfig.enabled = false（預設）
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // 只有 sendNotification（全局），不應呼叫 per-user 或 admin
            verify(mockWebhook, atLeastOnce()).sendNotification(anyString(), anyString(), anyInt());
            verify(mockWebhook, never()).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
            verify(mockWebhook, never()).sendNotificationToAdmins(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("多用戶模式 — 發 per-user + admin（不走 sendNotification 避免 MQ 重複）")
        void multiUserModeTwoWay() {
            multiUserConfig.setEnabled(true);
            TradeRecordService.setCurrentUserId("user-beck");
            TradeRecordService.setCurrentUserDisplayName("Beck Tsai (beck@example.com)");

            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("12345", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("SL failed"));

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // 多用戶模式不再呼叫 sendNotification（避免 MQ Consumer 重複派發到 admin per-user）
            verify(mockWebhook, never()).sendNotification(anyString(), anyString(), anyInt());
            // 1. 受影響用戶 per-user（不帶前綴）
            verify(mockWebhook, atLeastOnce()).sendNotificationToUser(
                    anyString(), anyString(), anyString(), anyInt());
            // 2. Admin（帶 displayName 前綴）
            verify(mockWebhook, atLeastOnce()).sendNotificationToAdmins(
                    anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("廣播 context 無持倉平倉 — 不發 notifyGlobal")
        void closeNoPositionBroadcastContextSkipsNotifyGlobal() {
            try {
                TradingOrchestrator.setBroadcastContext(true);

                when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
                when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

                TradeSignal signal = buildCloseSignal(1.0);
                service.executeClose(signal);

                // 廣播 context 下，無持倉平倉不應發 notifyGlobal
                verify(mockWebhook, never()).sendNotification(contains("無持倉"), anyString(), anyInt());
            } finally {
                TradingOrchestrator.clearBroadcastContext();
            }
        }
    }

    // ==================== 交易安全迴歸測試 ====================

    @Nested
    @DisplayName("交易安全迴歸 — 通知改動不影響核心交易")
    class TradingSafetyRegressionTests {

        @Test
        @DisplayName("通知服務拋例外 — 不影響進場流程")
        void notificationExceptionDoesNotBreakEntry() {
            setupEntryMocks(1000, 0, 95000);

            // 模擬通知服務拋例外
            doThrow(new RuntimeException("Discord webhook timeout"))
                    .when(mockWebhook).sendNotification(anyString(), anyString(), anyInt());

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 進場仍應成功
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any());
        }

        @Test
        @DisplayName("多用戶通知改動 — 進場 recordEntry 仍被正確呼叫")
        void multiUserNotificationDoesNotAffectRecordEntry() {
            multiUserConfig.setEnabled(true);
            TradeRecordService.setCurrentUserId("user-test");

            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 核心交易邏輯不受通知改動影響
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordEntry(any(), any(), any(), anyInt(), anyDouble(), any());
        }

        @Test
        @DisplayName("多用戶通知改動 — 平倉 recordClose 仍被正確呼叫")
        void multiUserNotificationDoesNotAffectRecordClose() {
            multiUserConfig.setEnabled(true);
            TradeRecordService.setCurrentUserId("user-test");

            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            when(mockAdapter.placeMarketOrder(anyString(), eq("SELL"), anyDouble())).thenReturn(closeOrder);

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(anyString(), any(), anyString());
        }
    }
}
