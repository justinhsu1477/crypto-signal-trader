package com.trader.trading.risk;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RiskManager {

    /**
     * Evaluate Martingale risk constraints and decide how many layers are allowed.
     * This is intentionally deterministic and reusable for any layered-entry strategy.
     */
    public RiskDecision evaluateMartingale(
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
        if (accountBalance <= 0) {
            return RiskDecision.reject("balance-unavailable");
        }

        // Trend filter: reject if short-term trend is weaker than long-term trend.
        if (ema50 < ema200) {
            return RiskDecision.reject("trend-filter-blocked");
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
}
