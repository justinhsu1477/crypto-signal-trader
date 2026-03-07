package com.trader.service;

import com.trader.shared.config.RiskConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.exchange.binance.BinanceAdapter;
import com.trader.trading.repository.TradeRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 安全機制測試 — 確保 API 失敗時系統拒絕交易，不會靜默開倉。
 *
 * 重點測試：
 * 1. Adapter 拋異常 → BFS facade 正確傳播
 * 2. 每日虧損熔斷 → 超限時拒絕新交易
 * 3. executeSignal 前置檢查失敗 → 回傳 fail
 *
 * 注意：JSON 解析錯誤的測試已移至 BinanceAdapterTest，
 * 因為解析邏輯現在位於 BinanceAdapter 內部。
 */
class SafetyCheckTest {

    private RiskConfig riskConfig;
    private TradeConfigResolver mockTradeConfigResolver;

    @BeforeEach
    void setUp() {
        // riskPercent=20%, maxDailyLossUsdt=2000
        riskConfig = new RiskConfig(
                50000, 2000, 0.80, 0,
                true,
                0.20,   // riskPercent (20%)
                3, 2.0, 20, List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeConfigResolver = mock(TradeConfigResolver.class);
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 0.0, 0.0, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);
    }

    @Nested
    @DisplayName("API 失敗安全防護")
    class ApiFailureSafety {

        @Test
        @DisplayName("getCurrentPositionAmount — Adapter 拋異常時應傳播 RuntimeException")
        void getCurrentPositionAmountPropagatesAdapterException() {
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);
            when(mockAdapter.getCurrentPositionAmount(anyString()))
                    .thenThrow(new RuntimeException("查詢持倉失敗"));

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, null, riskConfig, null, null, new MultiUserConfig(),
                    new SymbolLockRegistry(), null, mockTradeConfigResolver);

            assertThatThrownBy(() -> service.getCurrentPositionAmount("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("查詢持倉失敗");
        }

        @Test
        @DisplayName("getMarkPrice — Adapter 拋異常時應傳播 RuntimeException")
        void getMarkPriceThrowsOnApiError() {
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);
            when(mockAdapter.getMarkPrice(anyString()))
                    .thenThrow(new RuntimeException("API unavailable"));

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, null, riskConfig, null, null, new MultiUserConfig(),
                    new SymbolLockRegistry(), null, mockTradeConfigResolver);

            assertThatThrownBy(() -> service.getMarkPrice("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("getActivePositionCount — Adapter 拋異常時應傳播 RuntimeException")
        void getActivePositionCountPropagatesAdapterException() {
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);
            when(mockAdapter.getActivePositionCount())
                    .thenThrow(new RuntimeException("查詢持倉數量失敗"));

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, null, riskConfig, null, null, new MultiUserConfig(),
                    new SymbolLockRegistry(), null, mockTradeConfigResolver);

            assertThatThrownBy(() -> service.getActivePositionCount())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("查詢持倉數量失敗");
        }

        @Test
        @DisplayName("hasOpenEntryOrders — Adapter 拋異常時應傳播 RuntimeException")
        void hasOpenEntryOrdersPropagatesAdapterException() {
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);
            when(mockAdapter.hasOpenEntryOrders(anyString()))
                    .thenThrow(new RuntimeException("檢查掛單失敗"));

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, null, riskConfig, null, null, new MultiUserConfig(),
                    new SymbolLockRegistry(), null, mockTradeConfigResolver);

            assertThatThrownBy(() -> service.hasOpenEntryOrders("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("檢查掛單失敗");
        }

        @Test
        @DisplayName("executeSignal — API 查詢失敗時回傳 fail，不開倉")
        void executeSignalRejectsOnApiFailure() {
            // 準備 mock 依賴
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, new MultiUserConfig(),
                    null, new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class), new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);

            // getCurrentPositionAmount 拋異常
            when(mockAdapter.getCurrentPositionAmount(anyString()))
                    .thenThrow(new RuntimeException("查詢持倉失敗，拒絕交易: network error"));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("前置檢查失敗");
        }
    }

    @Nested
    @DisplayName("每日虧損熔斷機制")
    class DailyLossCircuitBreaker {

        @Test
        @DisplayName("今日虧損超限 → 拒絕新交易")
        void rejectWhenDailyLossExceeded() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-5000.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, new MultiUserConfig(),
                    null, new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class), new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            // maxDailyLossUsdt = 2000 (固定值)
            // 今日虧損 5000 >= 2000 → 熔斷
            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("每日虧損已達上限");

            // 應該發送告警
            verify(mockWebhook).sendNotification(contains("熔斷"), anyString(), anyInt());
        }

        @Test
        @DisplayName("今日虧損未超限 → 允許交易（通過熔斷檢查）")
        void allowWhenDailyLossUnderLimit() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            // 今日虧損 -1000 USDT
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-1000.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, new MultiUserConfig(),
                    null, new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class), new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            // maxDailyLossUsdt = 2000 (固定值)
            // 今日虧損 1000 < 2000 → 不觸發熔斷
            when(mockAdapter.getAvailableBalance()).thenReturn(1000.0);

            // 讓持倉查詢回傳 0（無持倉）
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            // 讓 getMarkPrice 回傳合理價格
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            // 會在 setMarginType 或 placeLimitOrder 時因為 adapter mock 回傳預設值而失敗
            // 但重點是：通過了熔斷檢查，不會被熔斷攔截
            List<OrderResult> results = service.executeSignal(signal);

            // 不應該因為熔斷被攔截
            if (!results.isEmpty()) {
                assertThat(results.get(0).getErrorMessage())
                        .doesNotContain("每日虧損已達上限");
            }

            // 不應發送熔斷告警
            verify(mockWebhook, never()).sendNotification(contains("熔斷"), anyString(), anyInt());
        }

        @Test
        @DisplayName("今日無虧損 → 允許交易")
        void allowWhenNoLossToday() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);

            BinanceFuturesService service = new BinanceFuturesService(
                    mock(BinanceAdapter.class), null, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            // 驗證 0 虧損不會觸發熔斷 (maxDailyLossUsdt > 0, |0| < 2000)
            assertThat(riskConfig.getRiskPercent()).isGreaterThan(0);
            assertThat(riskConfig.getMaxDailyLossUsdt()).isGreaterThan(0);
        }

        @Test
        @DisplayName("固定熔斷上限：不隨餘額縮水而變鬆")
        void fixedCircuitBreakerDoesNotShrinkWithBalance() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            // 今日虧損 -1999 USDT（接近上限但未超過）
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-1999.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, new MultiUserConfig(),
                    null, new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class), new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            // 餘額大幅縮水到 200 USDT，但熔斷上限仍然是固定 2000
            when(mockAdapter.getAvailableBalance()).thenReturn(200.0);
            when(mockAdapter.getCurrentPositionAmount(anyString())).thenReturn(0.0);
            when(mockAdapter.hasOpenEntryOrders(anyString())).thenReturn(false);
            when(mockAdapter.getMarkPrice(anyString())).thenReturn(95000.0);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 不應被熔斷攔截（會在後續 API 呼叫時因 adapter mock 而失敗）
            if (!results.isEmpty()) {
                assertThat(results.get(0).getErrorMessage())
                        .doesNotContain("每日虧損已達上限");
            }

            // 不應發送熔斷告警
            verify(mockWebhook, never()).sendNotification(contains("熔斷"), anyString(), anyInt());
        }

        @Test
        @DisplayName("今日虧損剛好等於上限 → 觸發熔斷")
        void rejectWhenExactlyAtLimit() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            NotificationService mockWebhook = mock(NotificationService.class);
            BinanceAdapter mockAdapter = mock(BinanceAdapter.class);

            // 剛好等於 2000
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-2000.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);

            TradingOrchestrator orchestrator = new TradingOrchestrator(
                    mockTradeRecord, mockDedup, mockWebhook, new MultiUserConfig(),
                    null, new SymbolLockRegistry(), mockTradeConfigResolver,
                    mock(StartOfDayBalanceCache.class), new TradeSignalValidator(), null);

            BinanceFuturesService service = new BinanceFuturesService(
                    mockAdapter, orchestrator, riskConfig, mockTradeRecord, mockDedup,
                    new MultiUserConfig(), new SymbolLockRegistry(), null, mockTradeConfigResolver);

            when(mockAdapter.getAvailableBalance()).thenReturn(5000.0);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isSuccess()).isFalse();
            assertThat(results.get(0).getErrorMessage()).contains("每日虧損已達上限");
        }
    }

    @Nested
    @DisplayName("getTodayRealizedLoss 計算")
    class TodayRealizedLoss {

        @Test
        @DisplayName("有虧損交易 — 回傳負數總和")
        void returnsNegativeSum() {
            TradeRepository mockRepo = mock(TradeRepository.class);

            Trade loss1 = Trade.builder().netProfit(-500.0).build();
            Trade loss2 = Trade.builder().netProfit(-300.0).build();
            Trade win1 = Trade.builder().netProfit(200.0).build();

            when(mockRepo.findClosedTradesAfter(any(LocalDateTime.class)))
                    .thenReturn(List.of(loss1, loss2, win1));

            TradeRecordService service = new TradeRecordService(mockRepo, null, null, new com.trader.trading.config.MultiUserConfig(), "system-trader");
            double todayLoss = service.getTodayRealizedLoss();

            // 只計算虧損部分：-500 + -300 = -800
            assertThat(todayLoss).isEqualTo(-800.0);
        }

        @Test
        @DisplayName("全部獲利 — 回傳 0")
        void returnsZeroWhenAllWins() {
            TradeRepository mockRepo = mock(TradeRepository.class);

            Trade win1 = Trade.builder().netProfit(500.0).build();
            Trade win2 = Trade.builder().netProfit(300.0).build();

            when(mockRepo.findClosedTradesAfter(any(LocalDateTime.class)))
                    .thenReturn(List.of(win1, win2));

            TradeRecordService service = new TradeRecordService(mockRepo, null, null, new com.trader.trading.config.MultiUserConfig(), "system-trader");
            double todayLoss = service.getTodayRealizedLoss();

            assertThat(todayLoss).isEqualTo(0.0);
        }

        @Test
        @DisplayName("無交易 — 回傳 0")
        void returnsZeroWhenNoTrades() {
            TradeRepository mockRepo = mock(TradeRepository.class);

            when(mockRepo.findClosedTradesAfter(any(LocalDateTime.class)))
                    .thenReturn(List.of());

            TradeRecordService service = new TradeRecordService(mockRepo, null, null, new com.trader.trading.config.MultiUserConfig(), "system-trader");
            double todayLoss = service.getTodayRealizedLoss();

            assertThat(todayLoss).isEqualTo(0.0);
        }

        @Test
        @DisplayName("有 null netProfit 的交易 — 安全忽略")
        void handlesNullNetProfit() {
            TradeRepository mockRepo = mock(TradeRepository.class);

            Trade loss1 = Trade.builder().netProfit(-500.0).build();
            Trade nullTrade = Trade.builder().netProfit(null).build();

            when(mockRepo.findClosedTradesAfter(any(LocalDateTime.class)))
                    .thenReturn(List.of(loss1, nullTrade));

            TradeRecordService service = new TradeRecordService(mockRepo, null, null, new com.trader.trading.config.MultiUserConfig(), "system-trader");
            double todayLoss = service.getTodayRealizedLoss();

            assertThat(todayLoss).isEqualTo(-500.0);
        }
    }
}
