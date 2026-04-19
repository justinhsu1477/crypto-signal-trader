package com.trader.trading.service.martingale;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.risk.MarketRiskScorer;
import com.trader.trading.risk.RiskScoreResult;
import com.trader.trading.service.BinanceFuturesService;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class MartingaleDecisionEngineTest {

    @Test
    void neverMode_alwaysReturnsFalse() {
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.NEVER, 60000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 58000, List.of(64000.0));

        assertThat(engine.shouldUseMartingale(signal)).isFalse();
    }

    @Test
    void alwaysMode_enabledWhenEnoughLayers() {
        // SL distance = (60000-56000)/60000 = 6.67%, step = 2% → 3 layers ≥ 2
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.ALWAYS, 60000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(64000.0));

        assertThat(engine.shouldUseMartingale(signal)).isTrue();
    }

    @Test
    void alwaysMode_disabledWhenTooFewLayers() {
        // SL distance = (60000-59500)/60000 = 0.83%, step = 2% → 0 layers < 2
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.ALWAYS, 60000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 59500, List.of(64000.0));

        assertThat(engine.shouldUseMartingale(signal)).isFalse();
    }

    @Test
    void autoMode_enabledWhenPoorRR() {
        // entry=60000, SL=56000 (risk=4000), TP=62000 (reward=2000) → R:R=0.5 < 3.0
        // SL distance = 6.67%, step = 2% → 3 layers ≥ 2
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 60000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(62000.0));

        assertThat(engine.shouldUseMartingale(signal)).isTrue();
    }

    @Test
    void autoMode_enabledWhenHighEntryDeviation() {
        // entry=60000, markPrice=62000 → deviation = 3.33% > step 2%
        // SL distance = 6.67%, 3 layers ≥ 2
        // R:R = (72000-60000)/(60000-56000) = 3.0, NOT < 3.0 → R:R check fails
        // But deviation check passes
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 62000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(72000.0));

        assertThat(engine.shouldUseMartingale(signal)).isTrue();
    }

    @Test
    void autoMode_disabledWhenGoodRRAndCloseEntry() {
        // entry=60000, markPrice=60100 → deviation = 0.17% < step 2%
        // SL=56000, TP=72000 → R:R = 12000/4000 = 3.0, NOT < 3.0
        // Both checks fail → disabled
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 60100);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(72000.0));

        assertThat(engine.shouldUseMartingale(signal)).isFalse();
    }

    @Test
    void nonEntrySignal_alwaysReturnsFalse() {
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.ALWAYS, 60000);
        TradeSignal signal = TradeSignal.builder()
                .symbol("BTCUSDT")
                .side(TradeSignal.Side.LONG)
                .signalType(TradeSignal.SignalType.CLOSE)
                .entryPriceLow(60000)
                .stopLoss(56000)
                .build();

        assertThat(engine.shouldUseMartingale(signal)).isFalse();
    }

    @Test
    void computeDynamicLayers_slDistanceDividedByStep() {
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 60000);
        // SL distance = 10%, step = 2% → floor(0.10/0.02) = 5, capped at maxLayers=5
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 54000, List.of(66000.0));

        assertThat(engine.computeDynamicLayers(signal)).isEqualTo(5);
    }

    @Test
    void computeDynamicLayers_cappedAtMaxLayers() {
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 60000);
        // SL distance = 20%, step = 2% → floor(0.20/0.02) = 10, capped at maxLayers=5
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 48000, List.of(72000.0));

        assertThat(engine.computeDynamicLayers(signal)).isEqualTo(5);
    }

    @Test
    void shortSignal_alwaysMode_worksCorrectly() {
        // SHORT: entry=60000, SL=64000 → distance = 6.67%, 3 layers
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.ALWAYS, 60000);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.SHORT, 60000, 64000, List.of(56000.0));

        assertThat(engine.shouldUseMartingale(signal)).isTrue();
        assertThat(engine.computeDynamicLayers(signal)).isEqualTo(3);
    }

    @Test
    void autoMode_disabledWhenRiskScoreBelowThreshold() {
        // 低 riskScore=20 < threshold=40 → 即使 R:R 差也跳過 Martingale
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.AUTO, 60000, 20);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(62000.0));

        assertThat(engine.shouldUseMartingale(signal)).isFalse();
    }

    @Test
    void alwaysMode_notAffectedByRiskScore() {
        // ALWAYS 模式不受 riskScore 影響
        MartingaleDecisionEngine engine = buildEngine(MartingaleDecisionEngine.DecisionMode.ALWAYS, 60000, 20);
        TradeSignal signal = buildSignal("BTCUSDT", TradeSignal.Side.LONG, 60000, 56000, List.of(64000.0));

        assertThat(engine.shouldUseMartingale(signal)).isTrue();
    }

    // === helpers ===

    private MartingaleDecisionEngine buildEngine(MartingaleDecisionEngine.DecisionMode mode, double markPrice) {
        return buildEngine(mode, markPrice, 100); // 預設 riskScore=100 通過 threshold
    }

    private MartingaleDecisionEngine buildEngine(MartingaleDecisionEngine.DecisionMode mode, double markPrice, int riskScore) {
        MartingaleStrategyConfig config = new MartingaleStrategyConfig(
                5, 0.02, 100.0, 2.0, 0.01, 0.30, 10000.0, 0.15,
                480, 60, 60000L, 5000L, 3, 0.008, 0.002,
                0, 0.02, 40, false, 120, 60, 0.002,
                mode, 2, 3.0, null
        );
        BinanceFuturesService binanceFuturesService = mock(BinanceFuturesService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        when(binanceFuturesService.getMarkPrice(anyString())).thenReturn(markPrice);

        MarketRiskScorer marketRiskScorer = mock(MarketRiskScorer.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        when(marketRiskScorer.evaluate(anyString(), any(), any())).thenReturn(
                new RiskScoreResult(riskScore, 25, 20, 25, 20, "mock"));

        return new MartingaleDecisionEngine(config, binanceFuturesService, marketRiskScorer);
    }

    private TradeSignal buildSignal(String symbol, TradeSignal.Side side, double entry, double sl, List<Double> tps) {
        return TradeSignal.builder()
                .symbol(symbol)
                .side(side)
                .signalType(TradeSignal.SignalType.ENTRY)
                .entryPriceLow(entry)
                .entryPriceHigh(entry)
                .stopLoss(sl)
                .takeProfits(tps)
                .build();
    }
}
