package com.trader.trading.service.martingale;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.service.BinanceFuturesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 判斷單一訊號是否適合使用 Martingale 分層入場。
 * 評估依據：SL 距離能容納的層數、R:R 比率、入場偏離度。
 */
@Slf4j
@Component
public class MartingaleDecisionEngine {

    public enum DecisionMode {
        /** 永不啟用 Martingale（預設，向後相容） */
        NEVER,
        /** 所有 ENTRY 訊號都用 Martingale */
        ALWAYS,
        /** 根據訊號品質自動判斷 */
        AUTO
    }

    /** AUTO 模式下，SL 空間至少能放幾層才啟用 Martingale */
    private static final int MIN_LAYERS_FOR_AUTO = 2;
    /** AUTO 模式下，R:R 低於此值 → Martingale 補強入場 */
    private static final double RR_THRESHOLD = 3.0;

    private final MartingaleStrategyConfig config;
    private final BinanceFuturesService binanceFuturesService;

    public MartingaleDecisionEngine(MartingaleStrategyConfig config,
                                     BinanceFuturesService binanceFuturesService) {
        this.config = config;
        this.binanceFuturesService = binanceFuturesService;
    }

    /**
     * 評估此訊號是否應使用 Martingale 策略入場。
     * @return true = 使用 Martingale 分層入場；false = 使用原始 Signal 策略
     */
    public boolean shouldUseMartingale(TradeSignal signal) {
        DecisionMode mode = config.getDecisionMode();
        if (mode == null || mode == DecisionMode.NEVER) {
            return false;
        }

        // 非 ENTRY 訊號不走 Martingale
        if (signal.getSignalType() != TradeSignal.SignalType.ENTRY) {
            return false;
        }

        if (signal.getSide() == null || signal.getStopLoss() <= 0) {
            return false;
        }

        if (mode == DecisionMode.ALWAYS) {
            return computeDynamicLayers(signal) >= MIN_LAYERS_FOR_AUTO;
        }

        // AUTO mode
        return evaluateAutoDecision(signal);
    }

    /**
     * AUTO 模式評估邏輯：
     * 1. SL 空間能容納 >= MIN_LAYERS_FOR_AUTO 層
     * 2. R:R < RR_THRESHOLD（品質不夠好 → Martingale 補強）
     *    或 entry deviation > stepPercent（市價離入場較遠 → 分層有利）
     */
    private boolean evaluateAutoDecision(TradeSignal signal) {
        int dynamicLayers = computeDynamicLayers(signal);
        if (dynamicLayers < MIN_LAYERS_FOR_AUTO) {
            log.debug("Martingale AUTO skip: {} layers < {} minimum", dynamicLayers, MIN_LAYERS_FOR_AUTO);
            return false;
        }

        // R:R ratio check — poor R:R benefits more from Martingale
        double rr = computeRiskRewardRatio(signal);
        if (rr > 0 && rr < RR_THRESHOLD) {
            log.info("Martingale AUTO enabled for {}: R:R={} < {}, layers={}",
                    signal.getSymbol(), String.format("%.2f", rr), RR_THRESHOLD, dynamicLayers);
            return true;
        }

        // Entry deviation check — price far from entry → layered entry beneficial
        double entryDeviation = computeEntryDeviation(signal);
        double stepPercent = config.getEffectiveStepPercent(signal.getSymbol());
        if (entryDeviation > stepPercent) {
            log.info("Martingale AUTO enabled for {}: entryDev={} > step={}, layers={}",
                    signal.getSymbol(),
                    String.format("%.4f", entryDeviation),
                    String.format("%.4f", stepPercent),
                    dynamicLayers);
            return true;
        }

        log.debug("Martingale AUTO skip for {}: R:R={}, entryDev={}, step={}",
                signal.getSymbol(),
                String.format("%.2f", rr),
                String.format("%.4f", entryDeviation),
                String.format("%.4f", stepPercent));
        return false;
    }

    /**
     * 動態層數 = SL 距離 ÷ 層間距，上限為 config.maxLayers
     */
    public int computeDynamicLayers(TradeSignal signal) {
        double slDistance = computeSlDistancePercent(signal);
        if (slDistance <= 0) {
            return 0;
        }
        double stepPercent = config.getEffectiveStepPercent(signal.getSymbol());
        if (stepPercent <= 0) {
            return 0;
        }
        int layers = (int) Math.floor(slDistance / stepPercent);
        return Math.min(Math.max(layers, 0), config.getEffectiveMaxLayers(signal.getSymbol()));
    }

    /**
     * SL 距離百分比（相對於入場價）
     */
    double computeSlDistancePercent(TradeSignal signal) {
        double entryPrice = signal.getEntryPriceLow();
        if (entryPrice <= 0) return 0;

        double sl = signal.getStopLoss();
        if (sl <= 0) return 0;

        return Math.abs(entryPrice - sl) / entryPrice;
    }

    /**
     * R:R = reward / risk（用第一個 TP 計算）
     */
    double computeRiskRewardRatio(TradeSignal signal) {
        double entryPrice = signal.getEntryPriceLow();
        if (entryPrice <= 0) return 0;

        double sl = signal.getStopLoss();
        double risk = Math.abs(entryPrice - sl);
        if (risk <= 0) return 0;

        if (signal.getTakeProfits() == null || signal.getTakeProfits().isEmpty()) {
            return 0;
        }
        double tp = signal.getTakeProfits().get(0);
        double reward = Math.abs(tp - entryPrice);
        return reward / risk;
    }

    /**
     * 入場偏離度 = |markPrice - entryPrice| / entryPrice
     */
    double computeEntryDeviation(TradeSignal signal) {
        try {
            double markPrice = binanceFuturesService.getMarkPrice(signal.getSymbol());
            double entryPrice = signal.getEntryPriceLow();
            if (markPrice <= 0 || entryPrice <= 0) return 0;
            return Math.abs(markPrice - entryPrice) / entryPrice;
        } catch (Exception e) {
            log.warn("Failed to get mark price for {}: {}", signal.getSymbol(), e.getMessage());
            return 0;
        }
    }
}
