package com.trader.trading.risk;

import com.trader.shared.model.TradeSignal;

/**
 * 市場條件過濾器，封裝不同的入場過濾策略。
 * 支援：多因子評分制（推薦）、EMA 趨勢過濾（舊版）、直接放行（測試用）。
 */
@FunctionalInterface
public interface MarketFilter {

    /**
     * 評估市場條件是否允許指定方向入場。
     * @return null 或 allowed=true 表示通過；rejected RiskDecision 表示拒絕。
     */
    RiskDecision evaluate(TradeSignal.Side side);

    /**
     * 多因子評分過濾：分數 >= 閾值才允許。
     */
    static MarketFilter riskScore(RiskScoreResult scoreResult, int threshold) {
        return side -> {
            if (scoreResult.meetsThreshold(threshold)) {
                return null; // 通過
            }
            return RiskDecision.reject(
                    "risk-score-below-threshold (" + scoreResult.totalScore() + "/" + threshold + ")");
        };
    }

    /**
     * EMA 趨勢過濾（舊版）：LONG 需黃金交叉，SHORT 需死亡交叉。
     */
    static MarketFilter ema(double ema50, double ema200) {
        return side -> {
            if (side == TradeSignal.Side.LONG && ema50 < ema200) {
                return RiskDecision.reject("trend-filter-blocked");
            }
            if (side == TradeSignal.Side.SHORT && ema50 > ema200) {
                return RiskDecision.reject("trend-filter-blocked");
            }
            return null; // 通過
        };
    }

    /**
     * 直接放行（用於測試或關閉所有市場過濾時）。
     */
    static MarketFilter passThrough() {
        return side -> null;
    }
}
