package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.shared.model.OrderResult;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradeRecordService 平倉記錄測試
 *
 * 測試重點：
 * 1. recordClose：全平盈虧計算（做多/做空、有手續費/無手續費）
 * 2. recordPartialClose：累加已平量 + 剩餘量 + 維持 OPEN
 * 3. 多次部分平倉後全平：數量追蹤正確
 * 4. calculateProfit 邊界案例
 * 5. recordCloseFromStream 全平 vs 部分判斷
 */
class TradeRecordCloseTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private TradeRecordService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        service = new TradeRecordService(tradeRepository, tradeEventRepository,
                new ObjectMapper(), new MultiUserConfig(), "system-trader");
    }

    // ==================== recordClose 盈虧計算 ====================

    @Nested
    @DisplayName("recordClose 盈虧計算")
    class RecordCloseProfit {

        @Test
        @DisplayName("做多獲利 — 有真實出場手續費")
        void longProfitWithRealCommission() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)  // maker 0.02%
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(97000).quantity(0.5)
                    .commission(19.4)  // 真實出場手續費
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 毛利 = (97000 - 95000) * 0.5 * 1 = 1000
            assertThat(trade.getGrossProfit()).isEqualTo(1000.0);
            // 手續費 = 入場 9.5 + 出場 19.4 = 28.9
            assertThat(trade.getCommission()).isEqualTo(28.9);
            // 淨利 = 1000 - 28.9 = 971.1
            assertThat(trade.getNetProfit()).isEqualTo(971.1);
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            assertThat(trade.getExitReason()).isEqualTo("SIGNAL_CLOSE");
        }

        @Test
        @DisplayName("做空獲利 — (entry - exit) × qty")
        void shortProfitCalculation() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("BUY").type("MARKET")
                    .price(93000).quantity(0.5)
                    .commission(18.6)
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 毛利 = (93000 - 95000) * 0.5 * (-1) = 1000（做空獲利）
            assertThat(trade.getGrossProfit()).isEqualTo(1000.0);
            assertThat(trade.getCommission()).isEqualTo(28.1);  // 9.5 + 18.6
            assertThat(trade.getNetProfit()).isEqualTo(971.9);  // 1000 - 28.1
        }

        @Test
        @DisplayName("做多虧損 — 淨利為負")
        void longLossCalculation() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(93000).quantity(0.5)
                    .commission(18.6)
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SL_TRIGGERED");

            // 毛利 = (93000 - 95000) * 0.5 * 1 = -1000
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);
            assertThat(trade.getNetProfit()).isEqualTo(-1028.1);  // -1000 - 28.1
        }

        @Test
        @DisplayName("無真實出場手續費 → fallback 估算 (taker 0.04%)")
        void commissionFallbackToEstimate() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(97000).quantity(0.5)
                    .commission(0)  // 無真實手續費
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 出場手續費估算 = 97000 * 0.5 * 0.0004 = 19.4
            // 總手續費 = 9.5 + 19.4 = 28.9
            assertThat(trade.getCommission()).isEqualTo(28.9);
        }

        @Test
        @DisplayName("找不到 OPEN trade → 靜默跳過，不拋例外")
        void closeWithNoOpenTrade() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET").price(97000).quantity(0.5)
                    .build();

            assertThatCode(() ->
                service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE")
            ).doesNotThrowAnyException();

            verify(tradeRepository, never()).save(any(Trade.class));
        }
    }

    // ==================== recordPartialClose ====================

    @Nested
    @DisplayName("recordPartialClose 部分平倉")
    class RecordPartialClose {

        @Test
        @DisplayName("首次部分平倉 50% — 追蹤剩餘量 + 維持 OPEN")
        void firstPartialClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(97000).quantity(0.5)
                    .build();

            service.recordPartialClose("BTCUSDT", closeOrder, 0.5, "SIGNAL_CLOSE");

            assertThat(trade.getStatus()).isEqualTo("OPEN");  // 維持 OPEN
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            assertThat(trade.getExitReason()).isEqualTo("SIGNAL_CLOSE_PARTIAL");
            assertThat(trade.getExitPrice()).isEqualTo(97000.0);
            assertThat(trade.getExitQuantity()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("連續兩次部分平倉 — totalClosedQuantity 累加正確")
        void twoPartialCloses() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 第一次部分平倉 30%
            OrderResult close1 = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(96000).quantity(0.3)
                    .build();
            service.recordPartialClose("BTCUSDT", close1, 0.3, "SIGNAL_CLOSE");

            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.3);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.7);

            // 第二次部分平倉 20%
            OrderResult close2 = OrderResult.builder()
                    .success(true).orderId("C2").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(97000).quantity(0.2)
                    .build();
            service.recordPartialClose("BTCUSDT", close2, 0.2, "SIGNAL_CLOSE");

            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            assertThat(trade.getStatus()).isEqualTo("OPEN");
        }
    }

    // ==================== recordCloseFromStream 全平 vs 部分 ====================

    @Nested
    @DisplayName("WebSocket Stream 平倉判斷")
    class StreamCloseParsing {

        @Test
        @DisplayName("做空止損 — status=CLOSED, 方向因子 direction=-1")
        void shortSlTriggered() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordCloseFromStream("BTCUSDT", 97000.0, 0.5,
                    19.4, -1000.0, "123", "SL_TRIGGERED", 1700000000000L);

            // 毛利 = (97000 - 95000) * 0.5 * (-1) = -1000（做空止損虧損）
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("部分平倉判斷 — exitQty < effectiveQty * 0.999 → PARTIAL")
        void partialCloseDetection() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 出場 0.5 BTC < 1.0 * 0.999 = 0.999 → 部分平倉
            service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "456", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("OPEN");
            assertThat(trade.getExitReason()).isEqualTo("TP_TRIGGERED_PARTIAL");
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            // 部分平倉不算淨利
            assertThat(trade.getNetProfit()).isNull();
        }

        @Test
        @DisplayName("容差判斷 — exitQty = effectiveQty * 0.9995 → 視為全平")
        void toleranceFullClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.500)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 出場 0.4999 BTC ≈ 0.5 * 0.9998 → 在 0.999 容差內 → 全平
            service.recordCloseFromStream("BTCUSDT", 96000.0, 0.4999,
                    9.6, 499.9, "789", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("有 remainingQuantity 的全平判斷 — 部分平倉過的 trade")
        void fullCloseAfterPartialClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)  // 已部分平倉
                    .totalClosedQuantity(0.5)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 出場 0.5 BTC = remainingQuantity → 全平
            service.recordCloseFromStream("BTCUSDT", 93000.0, 0.5,
                    9.3, -1000.0, "101", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 毛利 = (93000 - 95000) * 0.5 * 1 = -1000
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);
        }
    }

    // ==================== 其他記錄方法 ====================

    @Nested
    @DisplayName("其他記錄方法")
    class OtherRecordMethods {

        @Test
        @DisplayName("recordCancel — 標記 CANCELLED")
        void recordCancelMarksAsCancelled() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordCancel("BTCUSDT");

            assertThat(trade.getStatus()).isEqualTo("CANCELLED");
            assertThat(trade.getExitReason()).isEqualTo("CANCEL");
            verify(tradeRepository).save(trade);
        }

        @Test
        @DisplayName("recordMoveSL — 更新止損價 + 寫事件")
        void recordMoveSLUpdatesStopLoss() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).stopLoss(93000.0).status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult slOrder = OrderResult.builder()
                    .success(true).orderId("SL2").symbol("BTCUSDT")
                    .side("SELL").type("STOP_MARKET").price(94500).quantity(0.5)
                    .build();

            service.recordMoveSL("BTCUSDT", slOrder, 93000, 94500);

            assertThat(trade.getStopLoss()).isEqualTo(94500.0);
            verify(tradeRepository).save(trade);

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("MOVE_SL");
            assertThat(event.getPrice()).isEqualTo(94500.0);
        }

        @Test
        @DisplayName("recordProtectionLost — 記錄 SL_LOST 事件")
        void recordSLLost() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG").status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordProtectionLost("BTCUSDT", "STOP_MARKET", "SL123", "CANCELED");

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("SL_LOST");
            assertThat(event.getBinanceOrderId()).isEqualTo("SL123");
        }

        @Test
        @DisplayName("recordProtectionLost — 記錄 TP_LOST 事件")
        void recordTPLost() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG").status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordProtectionLost("BTCUSDT", "TAKE_PROFIT_MARKET", "TP456", "EXPIRED");

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("TP_LOST");
        }
    }
}
