package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.service.*;
import com.trader.user.service.UserApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BinanceFuturesService 核心交易邏輯測試
 *
 * 策略：spy 真實物件 + doReturn mock 內部 API 呼叫
 * 測試重點：進場流程、DCA、平倉、移動止損、風控
 */
class BinanceFuturesServiceTest {

    private RiskConfig riskConfig;
    private TradeRecordService mockTradeRecord;
    private SignalDeduplicationService mockDedup;
    private DiscordWebhookService mockWebhook;
    private UserApiKeyService mockUserApiKeyService;
    private TradeConfigResolver mockTradeConfigResolver;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        mockUserApiKeyService = mock(UserApiKeyService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);

        // mock TradeConfigResolver — 回傳與全局 RiskConfig 一致的 EffectiveTradeConfig
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook,
                new ObjectMapper(), new SymbolLockRegistry(), mockUserApiKeyService,
                mockTradeConfigResolver));

        // 通用 mock — 大部分測試需要的基礎環境
        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
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
        doReturn(balance).when(service).getAvailableBalance();
        doReturn(currentPosition).when(service).getCurrentPositionAmount(anyString());
        doReturn(0).when(service).getActivePositionCount();
        doReturn(false).when(service).hasOpenEntryOrders(anyString());
        doReturn(markPrice).when(service).getMarkPrice(anyString());
        doReturn("{}").when(service).setLeverage(anyString(), anyInt());
        try {
            doReturn("{}").when(service).setMarginType(anyString(), anyString());
        } catch (Exception e) { /* ignore */ }
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

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

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

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("SELL"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble());

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.SHORT, 95000, 97000);
            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("入場單失敗 → 回傳 fail + 記錄 ENTRY_FAILED 事件")
        void entryOrderFails() {
            setupEntryMocks(1000, 0, 95000);

            doReturn(OrderResult.fail("Insufficient margin")).when(service)
                    .placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());

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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(OrderResult.fail("SL placement failed")).when(service)
                    .placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("{}").when(service).cancelOrder(anyString(), anyLong());

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 應該嘗試取消入場單
            verify(service).cancelOrder(eq("BTCUSDT"), eq(12345L));
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
            when(mockDedup.isDuplicate(any())).thenReturn(true);

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

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

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
            verify(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
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

            // DCA 呼叫 cancelSLTPOrders（不是 cancelAllOrders）→ 內部呼叫 getOpenOrders
            doReturn("[]").when(service).getOpenOrders(anyString());

            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

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
    }

    // ==================== Close Flow ====================

    @Nested
    @DisplayName("平倉流程")
    class CloseFlow {

        @Test
        @DisplayName("全倉平倉成功")
        void fullCloseSuccess() {
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), eq("SELL"), anyDouble(), anyDouble());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("部分平倉 50% — SL 重掛剩餘倉位")
        void partialCloseWithSLRehang() {
            doReturn(1.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn(new double[]{93000.0, 100000.0}).when(service).getCurrentSLTPPrices(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            // 部分平倉後重掛 TP
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
            // cancelSLTPOrders 內部需要 getOpenOrders
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 應該重掛 SL
            verify(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("無持倉 → 取消所有掛單後返回")
        void closeNoPosition() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            // 沒有 fallback（DB 也沒有 OPEN trade）
            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            verify(service).cancelAllOrders(anyString());
        }
    }

    // ==================== Move SL ====================

    @Nested
    @DisplayName("移動止損流程")
    class MoveSLFlow {

        @Test
        @DisplayName("移動 SL 到新價格 — 成功")
        void moveSLSuccess() {
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

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
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            when(mockTradeRecord.getEntryPrice("BTCUSDT")).thenReturn(95000.0);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 95000, 0.5);
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = buildMoveSLSignal(null, null);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            // 應該用入場價 95000 而非 null
            verify(service).placeStopLoss(eq("BTCUSDT"), anyString(), eq(95000.0), anyDouble());
        }

        @Test
        @DisplayName("移動 SL + 更新 TP")
        void moveSLWithNewTP() {
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = buildMoveSLSignal(94500.0, 100000.0);
            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results.size()).isGreaterThanOrEqualTo(2);
            verify(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("無持倉 → 回傳 fail")
        void moveSLNoPosition() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());

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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(OrderResult.fail("SL failed")).when(service)
                    .placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doThrow(new RuntimeException("cancel failed")).when(service).cancelOrder(anyString(), anyLong());

            // 市價平倉
            OrderResult marketClose = successOrder("MC1", "SELL", 95000, 0.01);
            doReturn(marketClose).when(service).placeMarketOrder(anyString(), anyString(), anyDouble());

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> results = service.executeSignal(signal);

            // 應該嘗試市價平倉
            verify(service).placeMarketOrder(anyString(), anyString(), anyDouble());
        }
    }
}
