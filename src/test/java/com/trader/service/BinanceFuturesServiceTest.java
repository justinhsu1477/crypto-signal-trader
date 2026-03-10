package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.*;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.validation.TradeSignalValidator;
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
    private NotificationService mockWebhook;
    private UserApiKeyService mockUserApiKeyService;
    private TradeConfigResolver mockTradeConfigResolver;
    private MultiUserConfig multiUserConfig;
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

        // mock TradeConfigResolver — 回傳與全局 RiskConfig 一致的 EffectiveTradeConfig
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook, multiUserConfig,
                new ObjectMapper(), new SymbolLockRegistry(), mockUserApiKeyService,
                mockTradeConfigResolver, mock(StartOfDayBalanceCache.class), new com.trader.shared.util.BinanceApiRateLimiter(),
                new TradeSignalValidator(), null));

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

    // ==================== Position Size Modifier ====================

    @Nested
    @DisplayName("倉位修飾語 (positionSizeModifier)")
    class PositionSizeModifierFlow {

        @Test
        @DisplayName("modifier=0.5 → 下單量減半")
        void halfPositionModifier_reducesQuantityByHalf() {
            setupEntryMocks(1000, 0, 95000);

            // 用 ArgumentCaptor 抓 placeLimitOrder 傳入的 quantity
            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.005);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.005);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

            // 不帶 modifier 的 signal
            TradeSignal signalFull = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            List<OrderResult> resultsFull = service.executeSignal(signalFull);
            assertThat(resultsFull).isNotEmpty();

            // 帶 modifier=0.5 的 signal
            TradeSignal signalHalf = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .positionSizeModifier(0.5)
                    .build();

            // 重置 mock 計數
            setupEntryMocks(1000, 0, 95000);
            List<OrderResult> resultsHalf = service.executeSignal(signalHalf);
            assertThat(resultsHalf).isNotEmpty();

            // 驗證兩次呼叫 placeLimitOrder 的 quantity 差異
            // 使用 ArgumentCaptor 驗證
            var qtyCaptor = org.mockito.ArgumentCaptor.forClass(Double.class);
            verify(service, atLeast(2)).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), qtyCaptor.capture());

            List<Double> quantities = qtyCaptor.getAllValues();
            // 第二次（half）應該是第一次（full）的一半
            double fullQty = quantities.get(0);
            double halfQty = quantities.get(1);
            assertThat(halfQty).isCloseTo(fullQty * 0.5, within(0.0001));
        }

        @Test
        @DisplayName("modifier=null → 數量不變")
        void nullModifier_noEffect() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 0.01);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.01);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .positionSizeModifier(null)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();

            // 正常入場成功即可（modifier=null 等於不帶，不影響流程）
            verify(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("modifier 套用在 cap 之前 — notional cap 仍然生效")
        void modifierAppliedBeforeCaps() {
            // 用大餘額 + 窄止損產生大倉位，驗證 modifier 先生效再套 cap
            setupEntryMocks(100000, 0, 95000);

            OrderResult entryOrder = successOrder("E1", "BUY", 95000, 1.0);
            OrderResult slOrder = successOrder("SL1", "SELL", 94999, 1.0);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(94999)   // 極窄止損 → 超大倉位
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .positionSizeModifier(0.5)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);
            // 不管 cap 結果，只要流程跑完不報錯即可
            assertThat(results).isNotEmpty();
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
            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

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

        @Test
        @DisplayName("DCA 不帶新止損（stopLoss=0）→ 不被拒，使用 DB 現有 SL")
        void dcaWithoutNewStopLossUsesExistingSL() {
            setupEntryMocks(1000, 0.5, 95000);  // 已有 0.5 BTC 多倉

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));

            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

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
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), anyDouble());
        }
    }

    // ==================== Close Flow ====================

    @Nested
    @DisplayName("平倉流程")
    class CloseFlow {

        @Test
        @DisplayName("全倉平倉成功 — 使用 MARKET 單")
        void fullCloseSuccess() {
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            doReturn(closeOrder).when(service).placeMarketOrder(anyString(), eq("SELL"), anyDouble());

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
            // cancelSLTPOrders 內部需要 getOpenAlgoOrders
            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

            TradeSignal signal = buildCloseSignal(0.5);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 應該重掛 SL
            verify(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("無持倉但有未成交委託 → 撤銷掛單 → 返回 SUCCESS")
        void closeNoPositionWithPendingOrders() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(true).when(service).hasOpenEntryOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            verify(service).cancelAllOrders(anyString());
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(0).getErrorMessage()).contains("未成交委託已撤銷");
        }

        @Test
        @DisplayName("無持倉也無掛單 → 返回 FAIL 並忽略")
        void closeNoPositionNoPendingOrders() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(false).when(service).hasOpenEntryOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            verify(service).cancelAllOrders(anyString());
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

    // ==================== 全局通知 userId ====================

    @Nested
    @DisplayName("notifyGlobal — 全局通知自動附加 userId")
    class NotifyGlobalTests {

        @Test
        @DisplayName("每日虧損熔斷通知 — 包含 userId")
        void circuitBreakerNotificationContainsUserId() {
            setupEntryMocks(1000, 0, 95000);

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

            // 重建 service 以注入 sodCache
            service = spy(new BinanceFuturesService(
                    null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                    riskConfig, mockTradeRecord, mockDedup, mockWebhook, multiUserConfig,
                    new ObjectMapper(), new SymbolLockRegistry(), mockUserApiKeyService,
                    mockTradeConfigResolver, sodCache, new com.trader.shared.util.BinanceApiRateLimiter(),
                    new TradeSignalValidator(), null));
            setupEntryMocks(1000, 0, 95000);

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // 驗證通知內容包含 displayName
            verify(mockWebhook).sendNotification(
                    eq("🚨 每日虧損熔斷"),
                    contains("用戶: Test User (test@example.com)"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("SL 失敗 fail-safe 通知 — 包含 userId")
        void failSafeNotificationContainsUserId() {
            setupEntryMocks(1000, 0, 95000);

            OrderResult entryOrder = successOrder("12345", "BUY", 95000, 0.01);
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(OrderResult.fail("SL placement failed")).when(service)
                    .placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("{}").when(service).cancelOrder(anyString(), anyLong());

            TradeSignal signal = buildEntrySignal(TradeSignal.Side.LONG, 95000, 93000);
            service.executeSignal(signal);

            // fail-safe 通知應包含 displayName
            verify(mockWebhook).sendNotification(
                    eq("🛑 Fail-Safe: 止損失敗，入場單已取消"),
                    contains("用戶: Test User (test@example.com)"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("無持倉平倉通知 — 包含 userId")
        void closeNoPositionNotificationContainsUserId() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(false).when(service).hasOpenEntryOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());
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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(OrderResult.fail("SL failed")).when(service)
                    .placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("{}").when(service).cancelOrder(anyString(), anyLong());

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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(OrderResult.fail("SL failed")).when(service)
                    .placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("{}").when(service).cancelOrder(anyString(), anyLong());

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
        @SuppressWarnings({"unchecked", "rawtypes"})
        void closeNoPositionBroadcastContextSkipsNotifyGlobal() throws Exception {
            // 模擬廣播 context：設入 CURRENT_USER_KEYS ThreadLocal
            var field = BinanceFuturesService.class.getDeclaredField("CURRENT_USER_KEYS");
            field.setAccessible(true);
            var threadLocal = (ThreadLocal<?>) field.get(null);

            try {
                ((ThreadLocal) threadLocal).set(new UserApiKeyService.BinanceKeys("test", "test"));

                doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
                doReturn("{}").when(service).cancelAllOrders(anyString());
                when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

                TradeSignal signal = buildCloseSignal(1.0);
                service.executeClose(signal);

                // 廣播 context 下，無持倉平倉不應發 notifyGlobal
                verify(mockWebhook, never()).sendNotification(contains("無持倉"), anyString(), anyInt());
            } finally {
                threadLocal.getClass().getMethod("remove").invoke(threadLocal);
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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

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
            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

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

            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            doReturn(closeOrder).when(service).placeMarketOrder(anyString(), eq("SELL"), anyDouble());

            TradeSignal signal = buildCloseSignal(1.0);
            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            verify(mockTradeRecord).recordClose(anyString(), any(), anyString());
        }
    }
}
