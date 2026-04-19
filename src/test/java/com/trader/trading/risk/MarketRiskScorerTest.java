package com.trader.trading.risk;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.service.MarketIndicatorService;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class MarketRiskScorerTest {

    @Test
    void longWithExtremeNegativeFundingAndOversoldRSI_highScore() {
        MarketRiskScorer scorer = buildScorer(-0.0005, -0.06, 20.0, 0.02);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.LONG, buildConfig());

        // funding: -0.05% → 30, OI: -6% → 20, RSI: 20 → 30, ATR: 1x → 20
        assertEquals(30, result.fundingRateScore());
        assertEquals(20, result.openInterestScore());
        assertEquals(30, result.rsiScore());
        assertEquals(20, result.volatilityScore());
        assertEquals(100, result.totalScore());
        assertTrue(result.meetsThreshold(40));
    }

    @Test
    void longWithPositiveFundingAndHighRSI_lowScore() {
        MarketRiskScorer scorer = buildScorer(0.0005, 0.01, 65.0, 0.02);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.LONG, buildConfig());

        // funding: +0.05% → 0, OI: +1% → 5, RSI: 65 → 0, ATR: 1x → 20
        assertEquals(0, result.fundingRateScore());
        assertEquals(5, result.openInterestScore());
        assertEquals(0, result.rsiScore());
        assertEquals(25, result.totalScore());
        assertFalse(result.meetsThreshold(40));
    }

    @Test
    void shortWithExtremePositiveFundingAndOverboughtRSI_highScore() {
        MarketRiskScorer scorer = buildScorer(0.0005, -0.06, 80.0, 0.03);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.SHORT, buildConfig());

        // funding: +0.05% → 30, OI: -6% → 20, RSI: 80 → 30, ATR: 1.5x → 20
        assertEquals(30, result.fundingRateScore());
        assertEquals(20, result.openInterestScore());
        assertEquals(30, result.rsiScore());
        assertEquals(20, result.volatilityScore());
        assertEquals(100, result.totalScore());
    }

    @Test
    void shortWithNegativeFundingAndLowRSI_lowScore() {
        MarketRiskScorer scorer = buildScorer(-0.0003, 0.01, 30.0, 0.02);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.SHORT, buildConfig());

        // funding: -0.03% → 0, OI: +1% → 5, RSI: 30 → 0, ATR: 1x → 20
        assertEquals(0, result.fundingRateScore());
        assertEquals(5, result.openInterestScore());
        assertEquals(0, result.rsiScore());
        assertEquals(25, result.totalScore());
        assertFalse(result.meetsThreshold(40));
    }

    @Test
    void nanIndicators_giveMiddleScores() {
        MarketRiskScorer scorer = buildScorer(Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.LONG, buildConfig());

        // All NaN → all mid scores: 15 + 10 + 15 + 10 = 50
        assertEquals(15, result.fundingRateScore());
        assertEquals(10, result.openInterestScore());
        assertEquals(15, result.rsiScore());
        assertEquals(10, result.volatilityScore());
        assertEquals(50, result.totalScore());
        assertTrue(result.meetsThreshold(40));
    }

    @Test
    void extremelyLowVolatility_lowVolScore() {
        MarketRiskScorer scorer = buildScorer(0.0, 0.0, 40.0, 0.005);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.LONG, buildConfig());

        // ATR: 0.005 / 0.02 = 0.25 → ratio < 0.5 → 5
        assertEquals(5, result.volatilityScore());
    }

    @Test
    void extremelyHighVolatility_lowVolScore() {
        MarketRiskScorer scorer = buildScorer(0.0, 0.0, 40.0, 0.08);

        RiskScoreResult result = scorer.evaluate("BTCUSDT", TradeSignal.Side.LONG, buildConfig());

        // ATR: 0.08 / 0.02 = 4.0 → ratio > 3.0 → 5
        assertEquals(5, result.volatilityScore());
    }

    private MarketRiskScorer buildScorer(double fundingRate, double oiChange, double rsi, double atrPercent) {
        MarketIndicatorService indicatorService = mock(MarketIndicatorService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        when(indicatorService.getFundingRate(anyString())).thenReturn(fundingRate);
        when(indicatorService.getOpenInterestChange4h(anyString())).thenReturn(oiChange);
        when(indicatorService.getRSI(anyString(), eq(14))).thenReturn(rsi);
        when(indicatorService.getATRPercent(anyString(), eq(14))).thenReturn(atrPercent);
        return new MarketRiskScorer(indicatorService);
    }

    private MartingaleStrategyConfig buildConfig() {
        return new MartingaleStrategyConfig(
                5, 0.02, 100.0, 2.0, 0.01, 0.30, 10000.0, 0.15,
                480, 60, 60000L, 5000L, 3, 0.008, 0.002,
                14,     // atrPeriod = 14 → 啟用 ATR
                0.02,   // atrReferencePercent
                40,     // riskScoreThreshold
                false,  // emaFilterEnabled
                120, 60, 0.002,  // tpDecay params
                null,   // decisionMode
                2, 3.0, // decisionMinLayers, decisionRrThreshold
                null    // symbolOverrides
        );
    }
}
