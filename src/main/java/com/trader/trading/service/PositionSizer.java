package com.trader.trading.service;

import com.trader.trading.risk.LayerPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PositionSizer {

    /**
     * Distribute total allowed notional across layers by weight multipliers and convert to quantities.
     * baseWeight is a logical unit (not a real quantity); it controls relative layer sizing.
     */
    public List<LayerPlan> sizeLayers(
            List<Double> layerPrices,
            double baseWeight,
            double sizeMultiplier,
            double accountBalance,
            double riskPercent,
            int leverage,
            double maxPositionUsdt,
            double maxCapitalUsage
    ) {
        if (layerPrices == null || layerPrices.isEmpty()) {
            return List.of();
        }

        double effectiveLeverage = Math.max(1, leverage);
        double riskAmount = accountBalance * riskPercent;
        double maxNotionalByRisk = riskAmount * effectiveLeverage;
        double maxNotionalByCapitalUsage = accountBalance * maxCapitalUsage * effectiveLeverage;
        double maxNotional = Math.min(maxNotionalByRisk, maxNotionalByCapitalUsage);
        if (maxPositionUsdt > 0) {
            maxNotional = Math.min(maxNotional, maxPositionUsdt);
        }
        if (maxNotional <= 0) {
            return List.of();
        }

        int layers = layerPrices.size();
        double totalWeight = 0.0;
        double[] weights = new double[layers];
        for (int i = 0; i < layers; i++) {
            double weight = baseWeight * Math.pow(sizeMultiplier, i);
            weights[i] = weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            return List.of();
        }

        List<LayerPlan> plans = new ArrayList<>(layers);
        for (int i = 0; i < layers; i++) {
            double price = layerPrices.get(i);
            double notional = maxNotional * (weights[i] / totalWeight);
            double quantity = price > 0 ? (notional / price) : 0.0;
            plans.add(new LayerPlan(i + 1, price, quantity));
        }
        return plans;
    }
}
