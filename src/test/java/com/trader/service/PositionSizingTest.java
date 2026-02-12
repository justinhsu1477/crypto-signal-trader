package com.trader.service;

import com.trader.config.RiskConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 測試以損定倉的倉位計算和價格/數量格式化。
 *
 * 核心公式: qty = fixedLossPerTrade / |entryPrice - stopLoss|
 */
class PositionSizingTest {

    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        // fixedLossPerTrade = 500 USDT
        RiskConfig riskConfig = new RiskConfig(
                100, 10, 10, 5, 3.0, 3.0, true,
                500.0,  // fixedLossPerTrade
                1, 20, List.of("BTCUSDT", "ETHUSDT")
        );
        // 只需要 riskConfig，其他依賴傳 null（不會被 calculateFixedRiskQuantity 使用）
        service = new BinanceFuturesService(null, null, riskConfig, null, null);
    }

    @Nested
    @DisplayName("以損定倉 — calculateFixedRiskQuantity()")
    class FixedRiskQuantity {

        @Test
        @DisplayName("BTC 做多: entry=95000, SL=93000 → qty=0.25")
        void btcLong() {
            // riskDistance = |95000 - 93000| = 2000
            // qty = 500 / 2000 = 0.25
            double qty = service.calculateFixedRiskQuantity(95000, 93000);
            assertThat(qty).isEqualTo(0.25);
        }

        @Test
        @DisplayName("BTC 做空: entry=95000, SL=96000 → qty=0.5")
        void btcShort() {
            // riskDistance = |95000 - 96000| = 1000
            // qty = 500 / 1000 = 0.5
            double qty = service.calculateFixedRiskQuantity(95000, 96000);
            assertThat(qty).isEqualTo(0.5);
        }

        @Test
        @DisplayName("ETH 做多: entry=2650, SL=2580 → qty ≈ 7.14")
        void ethLong() {
            // riskDistance = |2650 - 2580| = 70
            // qty = 500 / 70 ≈ 7.142857
            double qty = service.calculateFixedRiskQuantity(2650, 2580);
            assertThat(qty).isCloseTo(7.1428, within(0.001));
        }

        @Test
        @DisplayName("小幣種大倉位: entry=0.50, SL=0.48 → qty=25000")
        void smallCoin() {
            // riskDistance = |0.50 - 0.48| = 0.02
            // qty = 500 / 0.02 = 25000
            double qty = service.calculateFixedRiskQuantity(0.50, 0.48);
            assertThat(qty).isCloseTo(25000.0, within(0.01));
        }

        @Test
        @DisplayName("SL 非常接近 entry → 超大倉位")
        void tightStopLoss() {
            // riskDistance = |95000 - 94999| = 1
            // qty = 500 / 1 = 500
            double qty = service.calculateFixedRiskQuantity(95000, 94999);
            assertThat(qty).isEqualTo(500.0);
        }

        @Test
        @DisplayName("entry = SL → 拋出異常")
        void sameEntryAndSl() {
            assertThatThrownBy(() -> service.calculateFixedRiskQuantity(95000, 95000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("入場價與止損價不可相同");
        }
    }

    @Nested
    @DisplayName("價格格式化 — formatPrice()")
    class FormatPrice {

        @Test
        @DisplayName("BTC (>=1000) → 1 位小數")
        void btcPrice() throws Exception {
            assertThat(invokeFormatPrice(95000.123)).isEqualTo("95000.1");
            assertThat(invokeFormatPrice(1000.0)).isEqualTo("1000.0");
        }

        @Test
        @DisplayName("ETH (>=1, <1000) → 2 位小數")
        void ethPrice() throws Exception {
            assertThat(invokeFormatPrice(500.567)).isEqualTo("500.57");
            assertThat(invokeFormatPrice(1.0)).isEqualTo("1.00");
            assertThat(invokeFormatPrice(999.0)).isEqualTo("999.00");
        }

        @Test
        @DisplayName(">=1000 的幣種 → 1 位小數 (ETH 2650 也屬於此區間)")
        void aboveThousand() throws Exception {
            assertThat(invokeFormatPrice(2650.567)).isEqualTo("2650.6");
            assertThat(invokeFormatPrice(1000.0)).isEqualTo("1000.0");
        }

        @Test
        @DisplayName("小幣種 (<1) → 4 位小數")
        void smallPrice() throws Exception {
            assertThat(invokeFormatPrice(0.5678)).isEqualTo("0.5678");
            assertThat(invokeFormatPrice(0.00123)).isEqualTo("0.0012");
        }

        private String invokeFormatPrice(double price) throws Exception {
            Method method = BinanceFuturesService.class.getDeclaredMethod("formatPrice", double.class);
            method.setAccessible(true);
            return (String) method.invoke(service, price);
        }
    }

    @Nested
    @DisplayName("數量格式化 — formatQuantity()")
    class FormatQuantity {

        @Test
        @DisplayName("BTC → 3 位小數")
        void btcQuantity() throws Exception {
            assertThat(invokeFormatQuantity("BTCUSDT", 0.25)).isEqualTo("0.250");
            assertThat(invokeFormatQuantity("BTCUSDT", 1.1234)).isEqualTo("1.123");
        }

        @Test
        @DisplayName("其他幣種 → 2 位小數")
        void otherQuantity() throws Exception {
            assertThat(invokeFormatQuantity("ETHUSDT", 7.1428)).isEqualTo("7.14");
            assertThat(invokeFormatQuantity("SOLUSDT", 100.0)).isEqualTo("100.00");
        }

        private String invokeFormatQuantity(String symbol, double quantity) throws Exception {
            Method method = BinanceFuturesService.class.getDeclaredMethod(
                    "formatQuantity", String.class, double.class);
            method.setAccessible(true);
            return (String) method.invoke(service, symbol, quantity);
        }
    }

    @Nested
    @DisplayName("RiskConfig 白名單")
    class SymbolWhitelist {

        @Test
        @DisplayName("白名單內的 symbol")
        void allowedSymbol() {
            RiskConfig config = new RiskConfig(
                    100, 10, 10, 5, 3.0, 3.0, true, 500.0, 1, 20,
                    List.of("BTCUSDT", "ETHUSDT")
            );
            assertThat(config.isSymbolAllowed("BTCUSDT")).isTrue();
            assertThat(config.isSymbolAllowed("ETHUSDT")).isTrue();
        }

        @Test
        @DisplayName("白名單外的 symbol")
        void disallowedSymbol() {
            RiskConfig config = new RiskConfig(
                    100, 10, 10, 5, 3.0, 3.0, true, 500.0, 1, 20,
                    List.of("BTCUSDT")
            );
            assertThat(config.isSymbolAllowed("ETHUSDT")).isFalse();
            assertThat(config.isSymbolAllowed("SOLUSDT")).isFalse();
        }
    }
}
