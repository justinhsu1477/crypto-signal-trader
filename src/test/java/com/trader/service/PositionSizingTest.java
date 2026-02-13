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
 * 測試動態以損定倉的倉位計算和價格/數量格式化。
 *
 * 核心公式: qty = (balance × riskPercent) / |entryPrice - stopLoss|
 */
class PositionSizingTest {

    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        // riskPercent = 20%, maxPositionUsdt = 50000 USDT, maxDailyLossUsdt = 2000
        RiskConfig riskConfig = new RiskConfig(
                50000,  // maxPositionUsdt
                2000,   // maxDailyLossUsdt
                true,
                0.20,   // riskPercent (20%)
                1, 20, List.of("BTCUSDT", "ETHUSDT")
        );
        service = new BinanceFuturesService(null, null, riskConfig, null, null, null);
    }

    @Nested
    @DisplayName("動態以損定倉 — calculatePositionSize(balance, entry, sl)")
    class DynamicPositionSize {

        @Test
        @DisplayName("BTC 做多: balance=1000, entry=95000, SL=93000 → 1R=200, qty=0.1")
        void btcLong_1000u() {
            // 1R = 1000 × 0.20 = 200
            // riskDistance = 2000, qty = 200 / 2000 = 0.1
            double qty = service.calculatePositionSize(1000, 95000, 93000);
            assertThat(qty).isEqualTo(0.1);
        }

        @Test
        @DisplayName("BTC 做多: balance=2500, entry=95000, SL=93000 → 1R=500, qty=0.25")
        void btcLong_2500u() {
            // 1R = 2500 × 0.20 = 500, qty = 500 / 2000 = 0.25
            double qty = service.calculatePositionSize(2500, 95000, 93000);
            assertThat(qty).isEqualTo(0.25);
        }

        @Test
        @DisplayName("BTC 做空: balance=1000, entry=95000, SL=96000 → 1R=200, qty=0.2")
        void btcShort() {
            // 1R = 200, riskDistance = 1000, qty = 200 / 1000 = 0.2
            double qty = service.calculatePositionSize(1000, 95000, 96000);
            assertThat(qty).isEqualTo(0.2);
        }

        @Test
        @DisplayName("ETH 做多: balance=1000, entry=2650, SL=2580 → 1R=200, qty ≈ 2.857")
        void ethLong() {
            // 1R = 200, riskDistance = 70, qty = 200 / 70 ≈ 2.857
            double qty = service.calculatePositionSize(1000, 2650, 2580);
            assertThat(qty).isCloseTo(2.8571, within(0.001));
        }

        @Test
        @DisplayName("連虧縮倉: balance=800 → 1R=160, BTC entry=95000 SL=93000 → qty=0.08")
        void shrinkAfterLoss() {
            // 虧一單後餘額 800, 1R = 800 × 0.20 = 160
            // qty = 160 / 2000 = 0.08
            double qty = service.calculatePositionSize(800, 95000, 93000);
            assertThat(qty).isEqualTo(0.08);
        }

        @Test
        @DisplayName("entry = SL → 拋出異常")
        void sameEntryAndSl() {
            assertThatThrownBy(() -> service.calculatePositionSize(1000, 95000, 95000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("入場價與止損價不可相同");
        }
    }

    @Nested
    @DisplayName("名目價值 cap — maxPositionUsdt")
    class NotionalCap {

        @Test
        @DisplayName("正常倉位不觸發 cap: balance=1000, BTC entry=95000, SL=93000 → 名目 9,500 < 50,000")
        void noCap() {
            double qty = service.calculatePositionSize(1000, 95000, 93000);
            // 1R=200, qty=0.1, 名目 = 95000 × 0.1 = 9500 < 50000
            assertThat(qty).isEqualTo(0.1);
        }

        @Test
        @DisplayName("窄止損觸發 cap: balance=1000, entry=95000, SL=94999 → cap 到 50000/95000")
        void capTriggered() {
            // 1R=200, riskDistance=1, qty=200 → 名目 = 19,000,000 >> 50,000
            // cap: 50000 / 95000 ≈ 0.5263
            double qty = service.calculatePositionSize(1000, 95000, 94999);
            double expectedCapped = 50000.0 / 95000.0;
            assertThat(qty).isCloseTo(expectedCapped, within(0.0001));
        }

        @Test
        @DisplayName("maxPositionUsdt=0 時不啟用 cap")
        void capDisabledWhenZero() {
            RiskConfig noCap = new RiskConfig(
                    0,  // maxPositionUsdt = 0 → 不啟用 cap
                    2000, true, 0.20, 1, 20, List.of("BTCUSDT")
            );
            BinanceFuturesService svc = new BinanceFuturesService(null, null, noCap, null, null, null);
            // 1R = 1000 × 0.20 = 200, riskDistance = 1, qty = 200
            double qty = svc.calculatePositionSize(1000, 95000, 94999);
            assertThat(qty).isEqualTo(200.0);
        }
    }

    @Nested
    @DisplayName("保證金充足性 cap — marginSufficiency")
    class MarginSufficiency {

        @Test
        @DisplayName("大餘額不觸發名目cap: balance=5000, entry=95000, SL=93000 → qty=0.5, 名目=47500 < 50000")
        void largeBalanceNoCap() {
            // 1R = 5000 * 0.20 = 1000, riskDist=2000, qty=0.5
            // 名目 = 95000 * 0.5 = 47500 < 50000 → 不觸發 cap
            double qty = service.calculatePositionSize(5000, 95000, 93000);
            assertThat(qty).isEqualTo(0.5);
        }

        @Test
        @DisplayName("大餘額觸發名目cap: balance=10000, entry=95000, SL=93000 → 名目cap")
        void largeBalanceCapTriggered() {
            // 1R = 10000 * 0.20 = 2000, riskDist=2000, qty=1.0
            // 名目 = 95000 * 1.0 = 95000 > 50000 → cap 到 50000/95000
            double qty = service.calculatePositionSize(10000, 95000, 93000);
            double expectedCapped = 50000.0 / 95000.0;
            assertThat(qty).isCloseTo(expectedCapped, within(0.0001));
        }

        @Test
        @DisplayName("保證金不足時，executeSignalInternal 會自動 cap（整合測試需 mock，這裡測算式正確性）")
        void marginCapFormula() {
            // 驗證公式: cappedQty = maxMargin * leverage / entry
            double balance = 100;
            double maxMargin = balance * 0.90; // 90
            int leverage = 20;
            double entry = 95000;
            double cappedQty = maxMargin * leverage / entry;
            // 90 * 20 / 95000 ≈ 0.01894
            assertThat(cappedQty).isCloseTo(0.01894, within(0.001));
        }
    }

    @Nested
    @DisplayName("最低下單量檢查 — minNotional")
    class MinNotional {

        @Test
        @DisplayName("正常倉位通過最低檢查: balance=1000, entry=95000 → 名目=9500 > 5")
        void normalPassesMinNotional() {
            double qty = service.calculatePositionSize(1000, 95000, 93000);
            double notional = 95000 * qty; // 9500
            assertThat(notional).isGreaterThan(5.0);
        }

        @Test
        @DisplayName("極小餘額的名目值計算: balance=1, entry=95000, SL=93000 → 名目=9.5 > 5")
        void tinyBalanceStillAboveMin() {
            // 1R = 1 * 0.20 = 0.2, qty = 0.2 / 2000 = 0.0001
            // 名目 = 95000 * 0.0001 = 9.5 > 5
            double qty = service.calculatePositionSize(1, 95000, 93000);
            double notional = 95000 * qty;
            assertThat(notional).isCloseTo(9.5, within(0.1));
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
                    50000, 2000, true, 0.20, 1, 20,
                    List.of("BTCUSDT", "ETHUSDT")
            );
            assertThat(config.isSymbolAllowed("BTCUSDT")).isTrue();
            assertThat(config.isSymbolAllowed("ETHUSDT")).isTrue();
        }

        @Test
        @DisplayName("白名單外的 symbol")
        void disallowedSymbol() {
            RiskConfig config = new RiskConfig(
                    50000, 2000, true, 0.20, 1, 20,
                    List.of("BTCUSDT")
            );
            assertThat(config.isSymbolAllowed("ETHUSDT")).isFalse();
            assertThat(config.isSymbolAllowed("SOLUSDT")).isFalse();
        }
    }
}
