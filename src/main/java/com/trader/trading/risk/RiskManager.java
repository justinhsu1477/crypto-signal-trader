package com.trader.trading.risk;

import com.trader.shared.model.TradeSignal;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RiskManager {

    /**
     * Evaluate Martingale risk constraints and decide how many layers are allowed.
     * Supports two market filter modes:
     * 1. Multi-factor risk score (recommended for crypto): riskScore + threshold
     * 2. Legacy EMA trend filter: ema50/ema200 crossover
     *
     * Mode is determined by the caller based on config (emaFilterEnabled / riskScoreThreshold).
     */
    public RiskDecision evaluateMartingale(
            TradeSignal.Side side,
            double accountBalance,
            double maxCapitalUsage,
            int maxLayers,
            double currentPositionSize,
            double maxPositionSize,
            int leverage,
            double currentDrawdownPercent,
            double maxDrawdownPercent,
            MarketFilter marketFilter,
            List<LayerPlan> layers
    ) {
        if (accountBalance <= 0) {
            return RiskDecision.reject("balance-unavailable");
        }

        // Market condition filter (EMA or multi-factor score)
        RiskDecision filterResult = marketFilter.evaluate(side);
        if (filterResult != null && !filterResult.allowed()) {
            return filterResult;
        }

        // Drawdown protection: stop if drawdown exceeds the configured threshold.
        if (currentDrawdownPercent > maxDrawdownPercent) {
            return RiskDecision.reject("drawdown-exceeded");
        }

        int cappedLayers = Math.min(maxLayers, layers.size());
        if (cappedLayers <= 0) {
            return RiskDecision.reject("no-layers-available");
        }

        // Position size protection: current size + planned layers must not exceed maxPositionSize.
        double currentSizeAbs = Math.abs(currentPositionSize);
        if (maxPositionSize > 0 && currentSizeAbs >= maxPositionSize) {
            return RiskDecision.reject("position-size-exceeded");
        }

        // Max capital usage: ensure total notional of allowed layers
        // does not exceed accountBalance * maxCapitalUsage.
        double capitalCap = accountBalance * maxCapitalUsage;
        double effectiveLeverage = Math.max(1, leverage);
        double cumulativeNotional = 0.0;
        double cumulativeQuantity = 0.0;
        int allowedLayers = 0;

        for (int i = 0; i < cappedLayers; i++) {
            LayerPlan plan = layers.get(i);
            cumulativeNotional += plan.notional();
            cumulativeQuantity += plan.quantity();

            if (maxPositionSize > 0 && (currentSizeAbs + cumulativeQuantity) > maxPositionSize) {
                break;
            }
            double marginUsage = cumulativeNotional / effectiveLeverage;
            if (marginUsage <= capitalCap) {
                allowedLayers = i + 1;
            } else {
                break;
            }
        }

        if (allowedLayers == 0) {
            return RiskDecision.reject("capital-usage-exceeded");
        }

        return RiskDecision.allow(allowedLayers);
    }

    /**
     * 向下相容：舊版 EMA 參數簽名，內部轉換為 MarketFilter。
     */
    public RiskDecision evaluateMartingale(
            TradeSignal.Side side,
            double accountBalance,
            double maxCapitalUsage,
            int maxLayers,
            double currentPositionSize,
            double maxPositionSize,
            int leverage,
            double currentDrawdownPercent,
            double maxDrawdownPercent,
            double ema50,
            double ema200,
            List<LayerPlan> layers
    ) {
        MarketFilter emaFilter = MarketFilter.ema(ema50, ema200);
        return evaluateMartingale(
                side, accountBalance, maxCapitalUsage, maxLayers,
                currentPositionSize, maxPositionSize, leverage,
                currentDrawdownPercent, maxDrawdownPercent,
                emaFilter, layers
        );
    }
}
