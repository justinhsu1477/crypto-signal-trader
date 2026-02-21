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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Symbol Fallback 機制 專項測試
 *
 * 場景：AI 發送 "BTCUSDT" CLOSE，但用戶實際持有 ETHUSDT
 * 系統會查 DB 找到唯一的 OPEN trade，自動切換到正確的 symbol
 *
 * 測試重點：
 * 1. DB 有恰好 1 筆 OPEN trade → fallback 成功
 * 2. DB 有 0 筆 OPEN trade → 取消掛單 + 回傳失敗
 * 3. DB 有 2+ 筆 OPEN trade → 無法自動決定 → 回傳失敗
 * 4. Fallback 後的 CLOSE 正常執行
 * 5. MOVE_SL 也支援 fallback
 */
class SymbolFallbackTest {

    private TradeRecordService mockTradeRecord;
    private DiscordWebhookService mockWebhook;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        UserApiKeyService mockApiKey = mock(UserApiKeyService.class);
        TradeConfigResolver mockTradeConfigResolver = mock(TradeConfigResolver.class);

        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook,
                new ObjectMapper(), new SymbolLockRegistry(), mockApiKey,
                mockTradeConfigResolver));
    }

    // ==================== CLOSE Symbol Fallback ====================

    @Nested
    @DisplayName("CLOSE 訊號 Symbol Fallback")
    class CloseSymbolFallback {

        @Test
        @DisplayName("DB 有 1 筆 OPEN (ETHUSDT) → fallback 成功，用 ETHUSDT 平倉")
        void fallbackToSingleOpenTrade() {
            // BTCUSDT 無持倉
            doReturn(0.0).when(service).getCurrentPositionAmount("BTCUSDT");
            // DB 有 1 筆 ETHUSDT OPEN trade
            Trade ethTrade = Trade.builder()
                    .tradeId("t1").symbol("ETHUSDT").side("LONG")
                    .entryPrice(3000.0).entryQuantity(1.0).status("OPEN")
                    .build();
            when(mockTradeRecord.findAllOpenTrades()).thenReturn(List.of(ethTrade));

            // ETHUSDT 有持倉
            doReturn(1.0).when(service).getCurrentPositionAmount("ETHUSDT");
            doReturn(3200.0).when(service).getMarkPrice("ETHUSDT");
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("ETHUSDT")
                    .side("SELL").type("MARKET").price(3200).quantity(1.0)
                    .build();
            doReturn(closeOrder).when(service).placeMarketOrder(eq("ETHUSDT"), eq("SELL"), anyDouble());

            TradeSignal closeSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")  // 原始訊號是 BTCUSDT
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();

            List<OrderResult> results = service.executeClose(closeSignal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 確認是用 ETHUSDT 平倉
            verify(service).placeMarketOrder(eq("ETHUSDT"), eq("SELL"), anyDouble());
            // 確認發送了 Symbol 自動修正通知
            verify(mockWebhook).sendNotification(contains("自動修正"), anyString(), anyInt());
        }

        @Test
        @DisplayName("DB 有 0 筆 OPEN → 取消所有掛單 + 回傳失敗")
        void noOpenTradesFallbackFails() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            when(mockTradeRecord.findAllOpenTrades()).thenReturn(List.of());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            TradeSignal closeSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();

            List<OrderResult> results = service.executeClose(closeSignal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
            // 應該嘗試取消掛單
            verify(service).cancelAllOrders("BTCUSDT");
        }

        @Test
        @DisplayName("DB 有 2 筆 OPEN → 無法自動決定，取消掛單")
        void multipleOpenTradesFallbackFails() {
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            Trade t1 = Trade.builder().tradeId("t1").symbol("ETHUSDT").side("LONG").status("OPEN").build();
            Trade t2 = Trade.builder().tradeId("t2").symbol("SOLUSDT").side("SHORT").status("OPEN").build();
            when(mockTradeRecord.findAllOpenTrades()).thenReturn(List.of(t1, t2));
            doReturn("{}").when(service).cancelAllOrders(anyString());

            TradeSignal closeSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();

            List<OrderResult> results = service.executeClose(closeSignal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isFalse();
        }
    }

    // ==================== MOVE_SL Symbol Fallback ====================

    @Nested
    @DisplayName("MOVE_SL 訊號 Symbol Fallback")
    class MoveSLSymbolFallback {

        @Test
        @DisplayName("MOVE_SL 也支援 fallback — BTCUSDT 無持倉 → 使用 ETHUSDT")
        void moveSLFallbackToOpenTrade() {
            // BTCUSDT 無持倉
            doReturn(0.0).when(service).getCurrentPositionAmount("BTCUSDT");

            // DB 有 1 筆 ETHUSDT OPEN trade
            Trade ethTrade = Trade.builder()
                    .tradeId("t1").symbol("ETHUSDT").side("LONG")
                    .entryPrice(3000.0).stopLoss(2800.0).status("OPEN")
                    .build();
            when(mockTradeRecord.findAllOpenTrades()).thenReturn(List.of(ethTrade));
            when(mockTradeRecord.findOpenTrade("ETHUSDT")).thenReturn(Optional.of(ethTrade));

            // ETHUSDT 有持倉
            doReturn(1.0).when(service).getCurrentPositionAmount("ETHUSDT");
            doReturn("{}").when(service).cancelAllOrders("ETHUSDT");

            OrderResult slOrder = OrderResult.builder()
                    .success(true).orderId("SL1").symbol("ETHUSDT")
                    .side("SELL").type("STOP_MARKET").price(3100).quantity(1.0)
                    .build();
            doReturn(slOrder).when(service).placeStopLoss(eq("ETHUSDT"), anyString(), anyDouble(), anyDouble());

            TradeSignal moveSLSignal = TradeSignal.builder()
                    .symbol("BTCUSDT")  // 原始訊號指向 BTCUSDT
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(3100.0)
                    .build();

            List<OrderResult> results = service.executeMoveSL(moveSLSignal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 確認是用 ETHUSDT
            verify(service).placeStopLoss(eq("ETHUSDT"), anyString(), eq(3100.0), anyDouble());
        }
    }
}
