package com.trader.service;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.validation.TradeSignalValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TradeSignalValidator 單元測試
 *
 * 測試策略：每條驗證規則至少 1 個正例 + 1 個反例
 */
class TradeSignalValidatorTest {

    private TradeSignalValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TradeSignalValidator();
    }

    // ==================== 基礎驗證 ====================

    @Nested
    @DisplayName("基礎 null / 空值驗證")
    class BasicValidation {

        @Test
        @DisplayName("null signal → 驗證失敗")
        void nullSignalFails() {
            Optional<String> result = validator.validate(null);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("不可為 null"));
        }

        @Test
        @DisplayName("symbol 為 null → 驗證失敗")
        void nullSymbolFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol(null)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(100)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("symbol 不可為空"));
        }

        @Test
        @DisplayName("symbol 為空白字串 → 驗證失敗")
        void blankSymbolFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("  ")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(100)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("symbol 不可為空"));
        }

        @Test
        @DisplayName("signalType 為 null → 驗證失敗")
        void nullSignalTypeFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(null)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("signalType 不可為 null"));
        }
    }

    // ==================== ENTRY 訊號驗證 ====================

    @Nested
    @DisplayName("ENTRY 訊號驗證")
    class EntryValidation {

        @Test
        @DisplayName("合法 ENTRY 訊號 → 通過驗證")
        void validEntryPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .entryPriceHigh(96000)
                    .stopLoss(93000)
                    .takeProfits(List.of(98000.0, 100000.0))
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("ENTRY 無 side（非 DCA）→ 驗證失敗")
        void entrySideRequiredForNonDca() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(null)
                    .isDca(false)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("side"));
        }

        @Test
        @DisplayName("DCA ENTRY side=null → 通過（從持倉推斷）")
        void dcaAllowsNullSide() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(null)
                    .isDca(true)
                    .entryPriceLow(95000)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("入場價 ≤ 0 → 驗證失敗")
        void entryPriceZeroFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(0)
                    .stopLoss(93000)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("入場價必須大於 0"));
        }

        @Test
        @DisplayName("入場價負數 → 驗證失敗")
        void entryPriceNegativeFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(-100)
                    .stopLoss(93000)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("入場價必須大於 0"));
        }

        @Test
        @DisplayName("入場價上限 < 下限 → 驗證失敗")
        void entryHighLessThanLowFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(96000)
                    .entryPriceHigh(95000)
                    .stopLoss(93000)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("入場價下限不可大於上限"));
        }

        @Test
        @DisplayName("入場價上限 == 下限 → 通過（單一入場價）")
        void entryHighEqualsLowPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .entryPriceHigh(95000)
                    .stopLoss(93000)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("止損價為負數 → 驗證失敗")
        void negativeStopLossFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(-100)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("止損價不可為負數"));
        }

        @Test
        @DisplayName("入場價 == 止損（非 DCA）→ 除以零風險，驗證失敗")
        void entryEqualsStopLossFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(95000)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("除以零"));
        }

        @Test
        @DisplayName("DCA 入場價 == 止損 → 允許（DCA 會從 DB 查詢）")
        void dcaEntryEqualsStopLossAllowed() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .entryPriceLow(95000)
                    .stopLoss(95000)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("止盈含 0 → 驗證失敗")
        void takeProfitZeroFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .takeProfits(List.of(98000.0, 0.0))
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("止盈價必須大於 0"));
        }

        @Test
        @DisplayName("止盈含負數 → 驗證失敗")
        void takeProfitNegativeFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .takeProfits(List.of(-500.0))
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("止盈價必須大於 0"));
        }
    }

    // ==================== CLOSE 訊號驗證 ====================

    @Nested
    @DisplayName("CLOSE 訊號驗證")
    class CloseValidation {

        @Test
        @DisplayName("合法 CLOSE — ratio=0.5 → 通過")
        void validCloseHalfPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("CLOSE ratio=null → 通過（全平）")
        void closeNullRatioPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(null)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("CLOSE ratio=1.0 → 通過")
        void closeFullRatioPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.0)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("CLOSE ratio=0 → 驗證失敗")
        void closeRatioZeroFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.0)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("平倉比例必須在 0-1 之間"));
        }

        @Test
        @DisplayName("CLOSE ratio=1.5 → 驗證失敗")
        void closeRatioExceedsOneFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(1.5)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("平倉比例必須在 0-1 之間"));
        }

        @Test
        @DisplayName("CLOSE ratio=-0.5 → 驗證失敗")
        void closeRatioNegativeFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(-0.5)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("平倉比例必須在 0-1 之間"));
        }
    }

    // ==================== MOVE_SL 訊號驗證 ====================

    @Nested
    @DisplayName("MOVE_SL 訊號驗證")
    class MoveSLValidation {

        @Test
        @DisplayName("MOVE_SL 有新止損 → 通過")
        void moveSLWithNewStopLossPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(94000.0)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("MOVE_SL 有新止盈 → 通過")
        void moveSLWithNewTakeProfitPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newTakeProfit(100000.0)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("MOVE_SL 有 takeProfits 列表 → 通過")
        void moveSLWithTakeProfitsListPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .takeProfits(List.of(99000.0, 101000.0))
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("MOVE_SL 全空 → 驗證失敗")
        void moveSLWithNothingFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(null)
                    .newTakeProfit(null)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("MOVE_SL 必須提供新止損或新止盈"));
        }

        @Test
        @DisplayName("MOVE_SL 新止損 ≤ 0 → 驗證失敗")
        void moveSLNegativeStopLossFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(-100.0)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("新止損價必須大於 0"));
        }

        @Test
        @DisplayName("MOVE_SL 新止盈 ≤ 0 → 驗證失敗")
        void moveSLNegativeTakeProfitFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newTakeProfit(0.0)
                    .build();
            Optional<String> result = validator.validate(signal);
            assertThat(result).isPresent().hasValueSatisfying(msg ->
                    assertThat(msg).contains("新止盈價必須大於 0"));
        }
    }

    // ==================== CANCEL / INFO 訊號 ====================

    @Nested
    @DisplayName("CANCEL / INFO 訊號")
    class CancelInfoValidation {

        @Test
        @DisplayName("CANCEL 訊號 → 直接通過")
        void cancelPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CANCEL)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }

        @Test
        @DisplayName("INFO 訊號 → 直接通過")
        void infoPasses() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.INFO)
                    .build();
            assertThat(validator.validate(signal)).isEmpty();
        }
    }
}
