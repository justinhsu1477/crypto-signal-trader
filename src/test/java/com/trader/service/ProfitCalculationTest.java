package com.trader.service;

import com.trader.entity.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 測試 TradeRecordService.calculateProfit() 的盈虧計算邏輯。
 *
 * 公式:
 *   grossProfit = (exitPrice - entryPrice) × qty × direction (LONG=+1, SHORT=-1)
 *   commission  = (entryPrice × qty × 0.0002) + (exitPrice × qty × 0.0002)
 *   netProfit   = grossProfit - commission
 */
class ProfitCalculationTest {

    /**
     * 透過反射呼叫 private calculateProfit() 方法。
     */
    private void invokeCalculateProfit(TradeRecordService service, Trade trade) throws Exception {
        Method method = TradeRecordService.class.getDeclaredMethod("calculateProfit", Trade.class);
        method.setAccessible(true);
        method.invoke(service, trade);
    }

    private TradeRecordService createService() {
        // calculateProfit() 不依賴 repository，傳 null 即可
        return new TradeRecordService(null, null);
    }

    @Nested
    @DisplayName("LONG 做多盈虧計算")
    class LongProfit {

        @Test
        @DisplayName("做多獲利 — BTC 95000 → 98000, qty=0.5")
        void longProfit() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(98000.0)
                    .entryQuantity(0.5)
                    .build();

            invokeCalculateProfit(createService(), trade);

            // grossProfit = (98000 - 95000) × 0.5 × 1 = 1500
            assertThat(trade.getGrossProfit()).isEqualTo(1500.0);

            // commission = (95000 × 0.5 × 0.0002) + (98000 × 0.5 × 0.0002)
            //            = 9.5 + 9.8 = 19.3
            assertThat(trade.getCommission()).isEqualTo(19.3);

            // netProfit = 1500 - 19.3 = 1480.7
            assertThat(trade.getNetProfit()).isEqualTo(1480.7);
        }

        @Test
        @DisplayName("做多虧損 — BTC 95000 → 93000, qty=0.5")
        void longLoss() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(93000.0)
                    .entryQuantity(0.5)
                    .build();

            invokeCalculateProfit(createService(), trade);

            // grossProfit = (93000 - 95000) × 0.5 × 1 = -1000
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);

            // commission = (95000 × 0.5 × 0.0002) + (93000 × 0.5 × 0.0002)
            //            = 9.5 + 9.3 = 18.8
            assertThat(trade.getCommission()).isEqualTo(18.8);

            // netProfit = -1000 - 18.8 = -1018.8
            assertThat(trade.getNetProfit()).isEqualTo(-1018.8);
        }
    }

    @Nested
    @DisplayName("SHORT 做空盈虧計算")
    class ShortProfit {

        @Test
        @DisplayName("做空獲利 — BTC 98000 → 95000, qty=0.2")
        void shortProfit() throws Exception {
            Trade trade = Trade.builder()
                    .side("SHORT")
                    .entryPrice(98000.0)
                    .exitPrice(95000.0)
                    .entryQuantity(0.2)
                    .build();

            invokeCalculateProfit(createService(), trade);

            // grossProfit = (95000 - 98000) × 0.2 × (-1) = 600
            assertThat(trade.getGrossProfit()).isEqualTo(600.0);

            // commission = (98000 × 0.2 × 0.0002) + (95000 × 0.2 × 0.0002)
            //            = 3.92 + 3.8 = 7.72
            assertThat(trade.getCommission()).isEqualTo(7.72);

            // netProfit = 600 - 7.72 = 592.28
            assertThat(trade.getNetProfit()).isEqualTo(592.28);
        }

        @Test
        @DisplayName("做空虧損 — ETH 2650 → 2750, qty=10")
        void shortLoss() throws Exception {
            Trade trade = Trade.builder()
                    .side("SHORT")
                    .entryPrice(2650.0)
                    .exitPrice(2750.0)
                    .entryQuantity(10.0)
                    .build();

            invokeCalculateProfit(createService(), trade);

            // grossProfit = (2750 - 2650) × 10 × (-1) = -1000
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);

            // commission = (2650 × 10 × 0.0002) + (2750 × 10 × 0.0002)
            //            = 5.3 + 5.5 = 10.8
            assertThat(trade.getCommission()).isEqualTo(10.8);

            // netProfit = -1000 - 10.8 = -1010.8
            assertThat(trade.getNetProfit()).isEqualTo(-1010.8);
        }
    }

    @Nested
    @DisplayName("邊界情境")
    class EdgeCases {

        @Test
        @DisplayName("entry/exit 相同 — grossProfit = 0, 但仍有手續費")
        void breakEvenTrade() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(95000.0)
                    .entryQuantity(0.1)
                    .build();

            invokeCalculateProfit(createService(), trade);

            assertThat(trade.getGrossProfit()).isEqualTo(0.0);
            // commission = 95000 × 0.1 × 0.0002 × 2 = 3.8
            assertThat(trade.getCommission()).isEqualTo(3.8);
            assertThat(trade.getNetProfit()).isEqualTo(-3.8);
        }

        @Test
        @DisplayName("entryPrice null — 不計算")
        void nullEntryPrice() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(null)
                    .exitPrice(98000.0)
                    .entryQuantity(0.5)
                    .build();

            invokeCalculateProfit(createService(), trade);

            assertThat(trade.getGrossProfit()).isNull();
            assertThat(trade.getCommission()).isNull();
            assertThat(trade.getNetProfit()).isNull();
        }

        @Test
        @DisplayName("exitPrice null — 不計算")
        void nullExitPrice() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(null)
                    .entryQuantity(0.5)
                    .build();

            invokeCalculateProfit(createService(), trade);

            assertThat(trade.getGrossProfit()).isNull();
        }

        @Test
        @DisplayName("entryQuantity null — 按 0 計算")
        void nullQuantity() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(98000.0)
                    .entryQuantity(null)
                    .build();

            invokeCalculateProfit(createService(), trade);

            assertThat(trade.getGrossProfit()).isEqualTo(0.0);
            assertThat(trade.getCommission()).isEqualTo(0.0);
            assertThat(trade.getNetProfit()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("大量交易 — ETH 做多, qty=1000")
        void largeQuantity() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(2000.0)
                    .exitPrice(2100.0)
                    .entryQuantity(1000.0)
                    .build();

            invokeCalculateProfit(createService(), trade);

            // grossProfit = (2100-2000) × 1000 × 1 = 100,000
            assertThat(trade.getGrossProfit()).isEqualTo(100000.0);

            // commission = (2000×1000×0.0002) + (2100×1000×0.0002) = 400 + 420 = 820
            assertThat(trade.getCommission()).isEqualTo(820.0);

            // netProfit = 100000 - 820 = 99180
            assertThat(trade.getNetProfit()).isEqualTo(99180.0);
        }
    }
}
