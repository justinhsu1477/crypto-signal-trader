package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.service.*;
import com.trader.user.service.UserApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 部分平倉 SL/TP 重掛邏輯 專項測試
 *
 * 測試重點：
 * 1. 部分平倉後 SL 重掛到正確價格 + 正確數量
 * 2. SL 重掛優先級：新 SL > 開倉價（成本保護）> 舊 SL
 * 3. 部分平倉後 TP 重掛
 * 4. 無任何 SL 資訊 → SL_REHUNG_FAILED 記錄
 * 5. 多次部分平倉的累計數量正確
 */
class PartialCloseTest {

    private TradeRecordService mockTradeRecord;
    private DiscordWebhookService mockWebhook;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        UserApiKeyService mockApiKey = mock(UserApiKeyService.class);
        TradeConfigResolver mockTradeConfigResolver = mock(TradeConfigResolver.class);

        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                List.of("BTCUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook,
                new ObjectMapper(), new SymbolLockRegistry(), mockApiKey,
                mockTradeConfigResolver));
    }

    private void setupCloseBaseMocks(double positionAmt, double oldSl, double oldTp) {
        doReturn(positionAmt).when(service).getCurrentPositionAmount(anyString());
        doReturn(95000.0).when(service).getMarkPrice(anyString());
        doReturn(new double[]{oldSl, oldTp}).when(service).getCurrentSLTPPrices(anyString());
        doReturn("{}").when(service).cancelAllOrders(anyString());
    }

    private OrderResult successOrder(String id, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(id).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    // ==================== SL 重掛優先級 ====================

    @Nested
    @DisplayName("部分平倉 SL 重掛")
    class SLRehang {

        @Test
        @DisplayName("有新 SL → 用新 SL 重掛（最高優先級）")
        void useNewSLFromSignal() {
            setupCloseBaseMocks(1.0, 93000, 100000);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .newStopLoss(94500.0)  // 帶新 SL
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            // 驗證用新 SL 價格 94500 重掛
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(94500.0), anyDouble());
        }

        @Test
        @DisplayName("無新 SL + 有舊 SL → 用舊 SL 重掛")
        void useOldSLWhenNoNewSL() {
            setupCloseBaseMocks(1.0, 93000, 100000);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    // 不帶 newStopLoss
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            // 驗證用舊 SL 價格 93000 重掛
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), anyDouble());
        }

        @Test
        @DisplayName("無新 SL + 無舊 SL + 有開倉價 → 成本保護")
        void costProtectionWhenNoSL() {
            // 無舊 SL (oldSl=0)
            setupCloseBaseMocks(1.0, 0, 0);

            when(mockTradeRecord.getEntryPrice("BTCUSDT")).thenReturn(95000.0);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 95000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            // 驗證用開倉價 95000 做成本保護
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(95000.0), anyDouble());
        }

        @Test
        @DisplayName("完全無 SL 資訊 → SL_REHUNG_FAILED 記錄 + 回傳失敗結果")
        void noSLInfoAvailable() {
            // 無舊 SL，也沒有開倉價
            setupCloseBaseMocks(1.0, 0, 0);
            when(mockTradeRecord.getEntryPrice("BTCUSDT")).thenReturn(null);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            // 應該記錄 SL_REHUNG_FAILED 事件
            verify(mockTradeRecord).recordOrderEvent(eq("BTCUSDT"), eq("SL_REHUNG_FAILED"), isNull(), anyString());
            // 結果中應有一個失敗的 SL 結果
            assertThat(results.stream().anyMatch(r -> !r.isSuccess())).isTrue();
        }

        @Test
        @DisplayName("SL 重掛數量應為剩餘倉位（非原始倉位）")
        void slRehangUsesRemainingQuantity() {
            setupCloseBaseMocks(1.0, 93000, 0);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            service.executeClose(signal);

            // 原始 1.0 BTC，平倉 0.5，剩餘應為 0.5
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
        }
    }

    // ==================== TP 重掛 ====================

    @Nested
    @DisplayName("部分平倉 TP 重掛")
    class TPRehang {

        @Test
        @DisplayName("有新 TP → 用新 TP 重掛")
        void useNewTPFromSignal() {
            setupCloseBaseMocks(1.0, 93000, 100000);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 105000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .newTakeProfit(105000.0)  // 帶新 TP
                    .build();

            service.executeClose(signal);

            // 驗證用新 TP 105000
            verify(service).placeTakeProfit(eq("BTCUSDT"), eq("SELL"), eq(105000.0), eq(0.5));
        }

        @Test
        @DisplayName("無新 TP + 有舊 TP → 保留舊 TP")
        void preserveOldTP() {
            setupCloseBaseMocks(1.0, 93000, 100000);

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    // 不帶 newTakeProfit
                    .build();

            service.executeClose(signal);

            // 驗證用舊 TP 100000
            verify(service).placeTakeProfit(eq("BTCUSDT"), eq("SELL"), eq(100000.0), eq(0.5));
        }

        @Test
        @DisplayName("無新 TP + 無舊 TP → 不掛 TP")
        void noTPToRehang() {
            setupCloseBaseMocks(1.0, 93000, 0);  // oldTp = 0

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            service.executeClose(signal);

            // 不應掛 TP
            verify(service, never()).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }
    }

    // ==================== 空倉部分平倉 ====================

    @Nested
    @DisplayName("空倉部分平倉")
    class ShortPartialClose {

        @Test
        @DisplayName("做空 50% 部分平倉 — 平倉方向 BUY + SL 重掛 BUY")
        void shortPartialCloseDirectionCorrect() {
            // 空倉 -1.0 BTC
            doReturn(-1.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn(new double[]{97000, 90000}).when(service).getCurrentSLTPPrices(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "BUY", 94000, 0.5);
            OrderResult slOrder = successOrder("SL1", "BUY", 97000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "BUY", 90000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn("[]").when(service).getOpenOrders(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            // 平倉應該用 BUY
            verify(service).placeLimitOrder(eq("BTCUSDT"), eq("BUY"), anyDouble(), anyDouble());
            // SL 重掛也用 BUY
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("BUY"), eq(97000.0), eq(0.5));
        }
    }
}
