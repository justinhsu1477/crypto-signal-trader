package com.trader.trading.risk;

/**
 * 多因子風控評分結果。
 * totalScore 為各指標分數加總（0~100），分數越高代表市場條件越有利於入場。
 */
public record RiskScoreResult(
        int totalScore,
        int fundingRateScore,
        int openInterestScore,
        int rsiScore,
        int volatilityScore,
        String breakdown
) {
    public boolean meetsThreshold(int threshold) {
        return totalScore >= threshold;
    }
}
