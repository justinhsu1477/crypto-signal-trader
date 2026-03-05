package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.shared.model.OrderResult;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 部分平倉 P&L 累計計算測試
 *
 * 驗證 partialProfit 欄位在部分平倉時正確累加，
 * 全平時 grossProfit 包含所有部分平倉的盈虧。
 *
 * Bug 修復前的問題：
 *   1 BTC LONG @ $95,000
 *   50% 平倉 @ $96,000 → +$500（未記錄）
 *   50% 平倉 @ $97,000 → 系統只記 $1,000，正確應為 $1,500
 */
class PartialClosePnlTest {

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

    // ==================== recordPartialClose partialProfit 累加 ====================

    @Nested
    @DisplayName("recordPartialClose — partialProfit 累加")
    class RecordPartialClosePnl {

        @Test
        @DisplayName("做多 50% 部分平倉獲利 — partialProfit = (96000-95000)*0.5 = 500")
        void longPartialCloseProfit() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(96000).quantity(0.5)
                    .build();

            service.recordPartialClose("BTCUSDT", closeOrder, 0.5, "SIGNAL_CLOSE");

            assertThat(trade.getPartialProfit()).isEqualTo(500.0);
            assertThat(trade.getNetProfit()).isNull();  // 尚未計算最終淨利
            assertThat(trade.getStatus()).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("做空 50% 部分平倉獲利 — direction=-1")
        void shortPartialCloseProfit() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("BUY").type("LIMIT").price(93000).quantity(0.5)
                    .build();

            service.recordPartialClose("BTCUSDT", closeOrder, 0.5, "SIGNAL_CLOSE");

            // partialProfit = (93000 - 95000) * 0.5 * (-1) = 1000（做空獲利）
            assertThat(trade.getPartialProfit()).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("做多 50% 部分平倉虧損 — partialProfit 為負")
        void longPartialCloseLoss() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(93000).quantity(0.5)
                    .build();

            service.recordPartialClose("BTCUSDT", closeOrder, 0.5, "SL_TRIGGERED");

            // partialProfit = (93000 - 95000) * 0.5 * 1 = -1000
            assertThat(trade.getPartialProfit()).isEqualTo(-1000.0);
        }

        @Test
        @DisplayName("多次部分平倉（30% + 30%）— partialProfit 累加兩次")
        void multiplePartialCloses() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 第一次 30% @ 96000
            OrderResult close1 = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(96000).quantity(0.3)
                    .build();
            service.recordPartialClose("BTCUSDT", close1, 0.3, "SIGNAL_CLOSE");

            // partialProfit = (96000-95000)*0.3 = 300
            assertThat(trade.getPartialProfit()).isEqualTo(300.0);

            // 第二次 30% @ 97000
            OrderResult close2 = OrderResult.builder()
                    .success(true).orderId("C2").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(97000).quantity(0.3)
                    .build();
            service.recordPartialClose("BTCUSDT", close2, 0.3, "SIGNAL_CLOSE");

            // partialProfit = 300 + (97000-95000)*0.3 = 300 + 600 = 900
            assertThat(trade.getPartialProfit()).isEqualTo(900.0);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.4);
        }
    }

    // ==================== recordCloseFromStream partialProfit 累加 ====================

    @Nested
    @DisplayName("WebSocket Stream 部分平倉 — partialProfit 累加")
    class StreamPartialClosePnl {

        @Test
        @DisplayName("Stream 部分平倉 — 累加 partialProfit")
        void streamPartialCloseAccumulatesProfit() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "456", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("OPEN");
            // partialProfit = (96000-95000)*0.5*1 = 500
            assertThat(trade.getPartialProfit()).isEqualTo(500.0);
            assertThat(trade.getNetProfit()).isNull();
        }
    }

    // ==================== 全平時 grossProfit 含 partialProfit ====================

    @Nested
    @DisplayName("全平倉 — grossProfit 包含 partialProfit")
    class FullCloseIncludesPartialProfit {

        @Test
        @DisplayName("核心場景：50%獲利+50%獲利 → 總毛利 = 兩次盈虧之和")
        void fullCloseAfterPartialBothProfit() {
            // 之前 50% @ 96000 → partialProfit = 500
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)
                    .totalClosedQuantity(0.5)
                    .partialProfit(500.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 全平剩餘 50% @ 97000
            service.recordCloseFromStream("BTCUSDT", 97000.0, 0.5,
                    9.7, 1000.0, "789", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 最終部分毛利 = (97000-95000)*0.5 = 1000
            // 總毛利 = 1000 + 500(partialProfit) = 1500
            assertThat(trade.getGrossProfit()).isEqualTo(1500.0);
            // 手續費 = 19.0(入場) + 9.7(出場) = 28.7
            assertThat(trade.getCommission()).isEqualTo(28.7);
            // 淨利 = 1500 - 28.7 = 1471.3
            assertThat(trade.getNetProfit()).isEqualTo(1471.3);
        }

        @Test
        @DisplayName("混合場景：50%獲利 + 50%虧損 → 總毛利正確抵消")
        void partialProfitPartialLoss() {
            // 之前 50% @ 96000 → partialProfit = +500
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)
                    .totalClosedQuantity(0.5)
                    .partialProfit(500.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 全平剩餘 50% @ 93000（虧損）
            service.recordCloseFromStream("BTCUSDT", 93000.0, 0.5,
                    9.3, -1000.0, "101", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 最終部分毛利 = (93000-95000)*0.5 = -1000
            // 總毛利 = -1000 + 500(partialProfit) = -500
            assertThat(trade.getGrossProfit()).isEqualTo(-500.0);
            // 手續費 = 19.0 + 9.3 = 28.3
            assertThat(trade.getCommission()).isEqualTo(28.3);
            // 淨利 = -500 - 28.3 = -528.3
            assertThat(trade.getNetProfit()).isEqualTo(-528.3);
        }

        @Test
        @DisplayName("做空場景：部分平倉獲利 + 全平獲利")
        void shortFullCloseWithPartialProfit() {
            // SHORT 50% @ 93000 → partialProfit = (93000-95000)*0.5*(-1) = 1000
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)
                    .totalClosedQuantity(0.5)
                    .partialProfit(1000.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 全平 50% @ 92000
            service.recordCloseFromStream("BTCUSDT", 92000.0, 0.5,
                    9.2, 1500.0, "202", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 最終毛利 = (92000-95000)*0.5*(-1) = 1500
            // 總毛利 = 1500 + 1000(partialProfit) = 2500
            assertThat(trade.getGrossProfit()).isEqualTo(2500.0);
        }

        @Test
        @DisplayName("無部分平倉的全平 — partialProfit=null，grossProfit 不受影響")
        void fullCloseWithoutPartialClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            service.recordCloseFromStream("BTCUSDT", 97000.0, 0.5,
                    9.7, 1000.0, "303", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 無 partialProfit，grossProfit = (97000-95000)*0.5 = 1000
            assertThat(trade.getGrossProfit()).isEqualTo(1000.0);
            assertThat(trade.getPartialProfit()).isNull();
        }

        @Test
        @DisplayName("多次部分平倉後全平 — 三段式計算")
        void multiplePartialsBeforeFullClose() {
            // 30% @ 96000 → +300, 20% @ 97000 → +400 → partialProfit = 700
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)
                    .totalClosedQuantity(0.5)
                    .partialProfit(700.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 全平剩餘 50% @ 98000
            service.recordCloseFromStream("BTCUSDT", 98000.0, 0.5,
                    9.8, 1500.0, "404", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 最終部分 = (98000-95000)*0.5 = 1500
            // 總毛利 = 1500 + 700 = 2200
            assertThat(trade.getGrossProfit()).isEqualTo(2200.0);
        }
    }

    // ==================== calculateProfit 含 partialProfit ====================

    @Nested
    @DisplayName("calculateProfit — 加總 partialProfit")
    class CalculateProfitWithPartial {

        private void invokeCalculateProfit(Trade trade) throws Exception {
            TradeRecordService svc = new TradeRecordService(null, null, null,
                    new MultiUserConfig(), "system-trader");
            Method method = TradeRecordService.class.getDeclaredMethod(
                    "calculateProfit", Trade.class, double.class);
            method.setAccessible(true);
            method.invoke(svc, trade, 0.0);
        }

        @Test
        @DisplayName("有 partialProfit 時 grossProfit 包含累計值")
        void calculateProfitIncludesPartialProfit() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(97000.0)
                    .exitQuantity(0.5)
                    .entryQuantity(1.0)
                    .partialProfit(500.0)  // 之前部分平倉累計
                    .build();

            invokeCalculateProfit(trade);

            // finalGross = (97000-95000)*0.5 = 1000
            // totalGross = 1000 + 500 = 1500
            assertThat(trade.getGrossProfit()).isEqualTo(1500.0);
        }

        @Test
        @DisplayName("partialProfit=null 時不影響原有計算")
        void calculateProfitWithoutPartialProfit() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(98000.0)
                    .entryQuantity(0.5)
                    .build();

            invokeCalculateProfit(trade);

            // grossProfit = (98000-95000)*0.5 = 1500（無 partialProfit）
            assertThat(trade.getGrossProfit()).isEqualTo(1500.0);
        }
    }
}
