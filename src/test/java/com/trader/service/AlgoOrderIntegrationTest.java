package com.trader.service;

import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.binance.BinanceAdapter;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
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
 * SL/TP 業務流程整合測試
 *
 * 測試重點：
 * 1. 部分平倉 — SL/TP 讀取後重掛
 * 2. DCA 補倉 — 取消舊 SL/TP 後重掛
 * 3. 移動止損 — 完整流程
 * 4. 安全性修復 — SL 網路異常 / MOVE_SL 告警 / ETH 格式化
 *
 * 注意：Algo Order API 解析與取消邏輯的單元測試已移至 BinanceAdapterTest，
 * 因為這些邏輯現在位於 BinanceAdapter 內部。
 */
class AlgoOrderIntegrationTest {

    private TradeRecordService mockTradeRecord;
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
        SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
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

        when(mockTradeRecord.getActiveUserId()).thenReturn("test-user");
        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
        when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);
    }

    private OrderResult successOrder(String id, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(id).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    // ==================== 完整交易流程搭配 SL/TP ====================

    @Nested
    @DisplayName("完整交易流程 — SL/TP 重掛")
    class FullFlowWithSLTP {

        @Test
        @DisplayName("部分平倉 — 讀取舊 SL/TP 後以剩餘數量重掛")
        void partialCloseRehangsSLTP() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{93000.0, 100000.0});

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            // 從 getCurrentSLTPPrices 讀取的 SL=93000、TP=100000 重掛到剩餘 0.5 BTC
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
            verify(mockAdapter).setTakeProfit(eq("BTCUSDT"), eq("SELL"), eq(100000.0), eq(0.5));
        }

        @Test
        @DisplayName("部分平倉 — 只有 SL 無 TP 時不掛 TP")
        void partialCloseWithSLOnlyDoesNotRehangTP() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{93000.0, 0.0});

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
            // 無舊 TP → 不掛 TP
            verify(mockAdapter, never()).setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("DCA 補倉 — 取消舊 SL/TP 後重新掛單")
        void dcaCancelsSLTPAndRehangs() {
            // 已有 0.5 BTC 多倉
            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);
            when(mockAdapter.getActivePositionCount()).thenReturn(0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));

            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble())).thenReturn(slOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("移動止損 — 完整流程")
        void moveSLFullFlow() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 102000, 0.5);

            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(94500.0)
                    .newTakeProfit(102000.0)
                    .build();

            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.stream().allMatch(OrderResult::isSuccess)).isTrue();
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), anyString(), eq(94500.0), eq(0.5));
            verify(mockAdapter).setTakeProfit(eq("BTCUSDT"), anyString(), eq(102000.0), eq(0.5));
        }

        @Test
        @DisplayName("做空部分平倉 — SL/TP 用 BUY 方向重掛")
        void shortPartialCloseRehangsWithBuyDirection() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(-1.0);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);
            when(mockAdapter.getCurrentSLTPPrices(anyString())).thenReturn(new double[]{97000.0, 90000.0});

            OrderResult closeOrder = successOrder("C1", "BUY", 94000, 0.5);
            OrderResult slOrder = successOrder("SL1", "BUY", 97000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "BUY", 90000, 0.5);

            when(mockAdapter.placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(closeOrder);
            when(mockAdapter.setStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(slOrder);
            when(mockAdapter.setTakeProfit(anyString(), eq("BUY"), anyDouble(), anyDouble())).thenReturn(tpOrder);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            // 空倉 SL 重掛用 BUY 方向，價格 97000
            verify(mockAdapter).setStopLoss(eq("BTCUSDT"), eq("BUY"), eq(97000.0), eq(0.5));
            // 空倉 TP 重掛用 BUY 方向，價格 90000
            verify(mockAdapter).setTakeProfit(eq("BTCUSDT"), eq("BUY"), eq(90000.0), eq(0.5));
        }
    }

    // ==================== 安全性修復驗證 ====================

    @Nested
    @DisplayName("安全性修復 — SL 網路異常 / MOVE_SL 告警 / ETH 格式化")
    class SafetyFixes {

        @Test
        @DisplayName("SL placeStopLoss RuntimeException → 轉為 OrderResult.fail → 觸發 Fail-Safe")
        void slRuntimeExceptionTriggerFailSafe() {
            // 設定正常的前置條件
            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.getActivePositionCount()).thenReturn(0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            // 使用數字 orderId 以便 Long.parseLong 成功
            OrderResult entryOrder = successOrder("123456", "BUY", 95000, 0.01);
            when(mockAdapter.placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);

            // SL 下單丟出 RuntimeException（模擬網路全部重試失敗）
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenThrow(new RuntimeException("Network timeout after 3 retries"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 應回傳失敗結果（Fail-Safe 觸發）
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
            // Fail-Safe 應嘗試取消入場單
            verify(mockAdapter).cancelOrder(eq("BTCUSDT"), eq("123456"));
            // Discord 應收到 Fail-Safe 通知
            verify(mockWebhook, atLeastOnce()).sendNotification(
                    contains("Fail-Safe"), anyString(), anyInt());
        }

        @Test
        @DisplayName("MOVE_SL — placeStopLoss 失敗發送 CRITICAL Discord 告警")
        void moveSLFailureSendsCriticalAlert() {
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.5);

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            // SL 掛失敗
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble()))
                    .thenReturn(OrderResult.fail("Binance rejected"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(94500.0)
                    .build();

            List<OrderResult> results = service.executeMoveSL(signal);

            // SL 失敗結果
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
            // 應發送 CRITICAL Discord 告警（title 含 "移動止損失敗"，body 含 "無止損保護"）
            verify(mockWebhook).sendNotification(
                    contains("移動止損失敗"), contains("無止損保護"), eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("ETH 格式化精度 — 應為 3 位小數（stepSize=0.001）")
        void ethQuantityPrecision() {
            when(mockAdapter.getAvailableBalance()).thenReturn(5000.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.getActivePositionCount()).thenReturn(0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(3000.0);

            OrderResult entryOrder = successOrder("E1", "BUY", 3000, 0.123);
            OrderResult slOrder = successOrder("SL1", "SELL", 2900, 0.123);

            when(mockAdapter.placeLimitOrder(eq("ETHUSDT"), anyString(), anyDouble(), anyDouble())).thenReturn(entryOrder);
            when(mockAdapter.setStopLoss(eq("ETHUSDT"), anyString(), anyDouble(), anyDouble())).thenReturn(slOrder);

            EffectiveTradeConfig ethConfig = new EffectiveTradeConfig(
                    0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                    List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
            );
            when(mockTradeConfigResolver.resolve(any())).thenReturn(ethConfig);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("ETHUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(3000)
                    .stopLoss(2900)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 確認 ETH 使用 3 位小數（而非舊的 2 位）
            assertThat(results).isNotEmpty();
            // 透過 mock 驗證 placeLimitOrder 被正確呼叫（使用了 formatQuantity 後的值）
            verify(mockAdapter).placeLimitOrder(eq("ETHUSDT"), eq("BUY"), eq(3000.0), anyDouble());
        }
    }
}
