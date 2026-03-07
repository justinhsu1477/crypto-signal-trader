package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.exchange.binance.BinanceAdapter;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.SignalDeduplicationService;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.service.TradingOrchestrator;
import com.trader.trading.validation.TradeSignalValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 告警通知測試 — 確保 TP 失敗時發送 Discord 通知。
 *
 * 注意：連線中斷告警（ConnectionFailureAlerts）和冪等重試（IdempotentRetry）
 * 測試已移至 BinanceAdapterTest 範疇，因為這些測試的是 Adapter 層級的 HTTP 行為。
 */
class AlertNotificationTest {

    private RiskConfig riskConfig;
    private TradeConfigResolver mockTradeConfigResolver;

    @BeforeEach
    void setUp() {
        riskConfig = new RiskConfig(
                50000, 2000, 0.80, 0,
                true,
                0.20, 3, 2.0, 20, List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeConfigResolver = mock(TradeConfigResolver.class);
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);
    }

    @Nested
    @DisplayName("TP 失敗告警")
    class TpFailureAlerts {

        @Test
        @DisplayName("ENTRY 流程 — TP 失敗應發送 Discord 黃色告警")
        void entryTpFailureSendsYellowAlert() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);
            when(mockDedup.generateHash(any())).thenReturn("testhash");

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook,
                    new MultiUserConfig(), new ObjectMapper(),
                    new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class),
                    new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig,
                    mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(),
                    null, mockTradeConfigResolver);

            // 餘額查詢 + 所有前置檢查通過
            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.getActivePositionCount()).thenReturn(0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            // 入場單和止損單成功
            OrderResult entryOk = OrderResult.builder()
                    .success(true).orderId("123").symbol("BTCUSDT").side("BUY")
                    .type("LIMIT").price(95000).quantity(0.25).build();
            OrderResult slOk = OrderResult.builder()
                    .success(true).orderId("124").symbol("BTCUSDT").side("SELL")
                    .type("STOP_MARKET").price(93000).quantity(0.25).build();
            // TP 失敗
            OrderResult tpFail = OrderResult.fail("TP order rejected by exchange");

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

            List<OrderResult> results = service.executeSignal(signal);

            // 入場和止損成功
            assertThat(results).hasSizeGreaterThanOrEqualTo(2);
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).isSuccess()).isTrue();

            // 應發送 TP 失敗黃色告警
            verify(mockWebhook).sendNotification(
                    contains("止盈單失敗"),
                    contains("請手動設定 TP"),
                    eq(NotificationService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("MOVE_SL 流程 — TP 失敗應發送 Discord 黃色告警")
        void moveSLTpFailureSendsYellowAlert() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);
            TradeConfigResolver localMockTradeConfigResolver = mock(TradeConfigResolver.class);

            EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                    0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                    List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
            );
            when(localMockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, null, mockWebhook,
                    new MultiUserConfig(), new ObjectMapper(),
                    new SymbolLockRegistry(), localMockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class),
                    new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig,
                    mockTradeRecord, null,
                    new MultiUserConfig(), new SymbolLockRegistry(),
                    null, localMockTradeConfigResolver);

            // 有持倉
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.25);
            // 查詢舊 SL
            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            // 新 SL 成功
            OrderResult slOk = OrderResult.builder()
                    .success(true).orderId("200").symbol("BTCUSDT").side("SELL")
                    .type("STOP_MARKET").price(94000).quantity(0.25).build();
            when(mockAdapter.setStopLoss(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(slOk);

            // 新 TP 失敗
            OrderResult tpFail = OrderResult.fail("TP error");
            when(mockAdapter.setTakeProfit(anyString(), anyString(), anyDouble(), anyDouble())).thenReturn(tpFail);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .newStopLoss(94000.0)
                    .takeProfits(List.of(98000.0))
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .build();

            List<OrderResult> results = service.executeMoveSL(signal);

            // SL 成功，TP 失敗
            assertThat(results).hasSize(2);
            assertThat(results.get(0).isSuccess()).isTrue();   // SL
            assertThat(results.get(1).isSuccess()).isFalse();  // TP

            // 應發送 TP 失敗黃色告警
            verify(mockWebhook).sendNotification(
                    contains("止盈單失敗"),
                    contains("請手動設定 TP"),
                    eq(NotificationService.COLOR_YELLOW));
        }
    }
}
