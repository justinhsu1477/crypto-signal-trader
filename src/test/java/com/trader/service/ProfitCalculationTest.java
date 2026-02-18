package com.trader.service;

import com.trader.trading.entity.Trade;
import com.trader.trading.service.TradeRecordService;
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
 *   commission  = (entryPrice × qty × 0.0002) + (exitPrice × qty × 0.0004)
 *                 入場 maker 0.02% + 出場 taker 0.04% (保守估算)
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
        return new TradeRecordService(null, null, null);
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

            // commission = (95000 × 0.5 × 0.0002) + (98000 × 0.5 × 0.0004)
            //            = 9.5 + 19.6 = 29.1
            assertThat(trade.getCommission()).isEqualTo(29.1);

            // netProfit = 1500 - 29.1 = 1470.9
            assertThat(trade.getNetProfit()).isEqualTo(1470.9);
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

            // commission = (95000 × 0.5 × 0.0002) + (93000 × 0.5 × 0.0004)
            //            = 9.5 + 18.6 = 28.1
            assertThat(trade.getCommission()).isEqualTo(28.1);

            // netProfit = -1000 - 28.1 = -1028.1
            assertThat(trade.getNetProfit()).isEqualTo(-1028.1);
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

            // commission = (98000 × 0.2 × 0.0002) + (95000 × 0.2 × 0.0004)
            //            = 3.92 + 7.6 = 11.52
            assertThat(trade.getCommission()).isEqualTo(11.52);

            // netProfit = 600 - 11.52 = 588.48
            assertThat(trade.getNetProfit()).isEqualTo(588.48);
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

            // commission = (2650 × 10 × 0.0002) + (2750 × 10 × 0.0004)
            //            = 5.3 + 11.0 = 16.3
            assertThat(trade.getCommission()).isEqualTo(16.3);

            // netProfit = -1000 - 16.3 = -1016.3
            assertThat(trade.getNetProfit()).isEqualTo(-1016.3);
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
            // commission = (95000 × 0.1 × 0.0002) + (95000 × 0.1 × 0.0004)
            //            = 1.9 + 3.8 = 5.7
            assertThat(trade.getCommission()).isEqualTo(5.7);
            assertThat(trade.getNetProfit()).isEqualTo(-5.7);
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

            // commission = (2000×1000×0.0002) + (2100×1000×0.0004) = 400 + 840 = 1240
            assertThat(trade.getCommission()).isEqualTo(1240.0);

            // netProfit = 100000 - 1240 = 98760
            assertThat(trade.getNetProfit()).isEqualTo(98760.0);
        }
    }
}
