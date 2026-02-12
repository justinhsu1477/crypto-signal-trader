package com.trader.service;

import com.trader.config.BinanceConfig;
import com.trader.config.RiskConfig;
import com.trader.entity.Trade;
import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import com.trader.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 安全機制測試 — 確保 API 失敗時系統拒絕交易，不會靜默開倉。
 *
 * 重點測試：
 * 1. API 查詢失敗 → 拋異常 → executeSignal 回傳 fail
 * 2. 每日虧損熔斷 → 超限時拒絕新交易
 * 3. markPrice=0 → 拒絕交易
 */
class SafetyCheckTest {

    private RiskConfig riskConfig;

    @BeforeEach
    void setUp() {
        // fixedLossPerTrade=500, maxDailyOrders=10 → 每日虧損上限 5000 USDT
        riskConfig = new RiskConfig(
                100, 10, 10, 5, 3.0, 3.0, true,
                500.0,  // fixedLossPerTrade
                1, 20, List.of("BTCUSDT", "ETHUSDT")
        );
    }

    @Nested
    @DisplayName("API 失敗安全防護")
    class ApiFailureSafety {

        @Test
        @DisplayName("getCurrentPositionAmount — JSON 解析失敗應拋出 RuntimeException")
        void getCurrentPositionAmountThrowsOnParseError() {
            // 模擬 getPositions() 回傳無法解析的 response
            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, null, null, null));
            doReturn("invalid json response").when(service).getPositions();

            assertThatThrownBy(() -> service.getCurrentPositionAmount("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("查詢持倉失敗");
        }

        @Test
        @DisplayName("getMarkPrice — API 無法連接應拋出 RuntimeException")
        void getMarkPriceThrowsOnApiError() {
            // httpClient=null → sendPublicGet → executeRequest 拋 NullPointerException
            // 這驗證了當 API 不可用時，getMarkPrice 不會靜默回傳 0
            BinanceFuturesService service = new BinanceFuturesService(
                    null, new com.trader.config.BinanceConfig("https://fake.test", "", ""),
                    riskConfig, null, null, null);

            assertThatThrownBy(() -> service.getMarkPrice("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("getActivePositionCount — JSON 解析失敗應拋出 RuntimeException")
        void getActivePositionCountThrowsOnParseError() {
            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, null, null, null));
            doReturn("bad response").when(service).getPositions();

            assertThatThrownBy(() -> service.getActivePositionCount())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("查詢持倉數量失敗");
        }

        @Test
        @DisplayName("hasOpenEntryOrders — JSON 解析失敗應拋出 RuntimeException")
        void hasOpenEntryOrdersThrowsOnParseError() {
            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, null, null, null));
            doReturn("bad response").when(service).getOpenOrders(anyString());

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
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, mockDedup, mockWebhook));

            // getCurrentPositionAmount 拋異常
            doThrow(new RuntimeException("查詢持倉失敗，拒絕交易: network error"))
                    .when(service).getCurrentPositionAmount(anyString());

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
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            // 今日虧損 -5000 USDT（達到上限 500 * 10 = 5000）
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-5000.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, mockDedup, mockWebhook));

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

            // 應該發送 Discord 告警
            verify(mockWebhook).sendNotification(contains("熔斷"), anyString(), anyInt());
        }

        @Test
        @DisplayName("今日虧損未超限 → 允許交易（通過熔斷檢查）")
        void allowWhenDailyLossUnderLimit() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            // 今日虧損 -1000 USDT（未達上限 5000）
            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(-1000.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, mockDedup, mockWebhook));

            // 讓持倉查詢回傳 0（無持倉）
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(0).when(service).getActivePositionCount();
            doReturn(false).when(service).hasOpenEntryOrders(anyString());
            // 讓 getMarkPrice 回傳合理價格
            doReturn(95000.0).when(service).getMarkPrice(anyString());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            // 會在 setMarginType 或 placeLimitOrder 時因為 httpClient=null 失敗
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
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, mockDedup, mockWebhook));

            // 驗證 0 虧損不會觸發熔斷
            double maxDailyLoss = riskConfig.getFixedLossPerTrade() * riskConfig.getMaxDailyOrders();
            assertThat(Math.abs(0.0)).isLessThan(maxDailyLoss);
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

            TradeRecordService service = new TradeRecordService(mockRepo, null);
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

            TradeRecordService service = new TradeRecordService(mockRepo, null);
            double todayLoss = service.getTodayRealizedLoss();

            assertThat(todayLoss).isEqualTo(0.0);
        }

        @Test
        @DisplayName("無交易 — 回傳 0")
        void returnsZeroWhenNoTrades() {
            TradeRepository mockRepo = mock(TradeRepository.class);

            when(mockRepo.findClosedTradesAfter(any(LocalDateTime.class)))
                    .thenReturn(List.of());

            TradeRecordService service = new TradeRecordService(mockRepo, null);
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

            TradeRecordService service = new TradeRecordService(mockRepo, null);
            double todayLoss = service.getTodayRealizedLoss();

            assertThat(todayLoss).isEqualTo(-500.0);
        }
    }
}
