package com.trader.trading.config;

import com.trader.trading.service.martingale.MartingaleDecisionEngine;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@Getter
@ConfigurationProperties(prefix = "trading.strategy.martingale")
public class MartingaleStrategyConfig {

    private final int maxLayers;
    private final double stepPercent;
    private final double baseSize;
    private final double sizeMultiplier;
    private final double takeProfitPercent;
    private final double maxCapitalUsage;
    private final double maxPositionSize;
    private final double stopLossPercent;
    /** 有成交（filledLayers > 0）的 session 最大持續時間（分鐘） */
    private final int sessionMaxDurationMinutes;
    /** 無成交（純掛單）的 session 閒置超時（分鐘），超時後取消掛單但不平倉 */
    private final int entryIdleTimeoutMinutes;
    private final long sessionCleanupIntervalMillis;
    private final long stopLossCheckIntervalMillis;
    private final int maxConcurrentSessions;
    /** 保本觸發閾值：markPrice 超過 avgPrice × (1 + trigger) 時啟動。0 = 停用 */
    private final double breakevenTriggerPercent;
    /** 保本 TP 偏移：觸發後 TP 移到 avgPrice × (1 + offset)（微利出場） */
    private final double breakevenOffsetPercent;
    /** ATR 計算週期（K 線數量），0 = 停用 ATR 自適應 */
    private final int atrPeriod;
    /** ATR 參考值（佔價格百分比）。ATR% = reference 時 stepPercent 不變 */
    private final double atrReferencePercent;
    /** 多因子風控評分閾值（0~100）。分數 >= 此值才允許入場。0 = 停用評分，改用 EMA */
    private final int riskScoreThreshold;
    /** 是否啟用 EMA 趨勢過濾（建議加密貨幣市場關閉） */
    private final boolean emaFilterEnabled;
    /** TP 時間衰減：衰減開始時間（分鐘），0 = 停用。持倉超過此時間後 TP 開始逐步降低 */
    private final int tpDecayStartMinutes;
    /** TP 時間衰減：每個衰減階段的間隔（分鐘） */
    private final int tpDecayIntervalMinutes;
    /** TP 時間衰減：最低 TP 百分比（衰減到此值後不再降低） */
    private final double tpDecayFloorPercent;
    /** Martingale 決策模式：NEVER（預設）、ALWAYS、AUTO */
    private final MartingaleDecisionEngine.DecisionMode decisionMode;
    /** AUTO 模式下，SL 空間至少能放幾層才啟用 Martingale */
    private final int decisionMinLayers;
    /** AUTO 模式下，R:R 低於此值才啟用 Martingale */
    private final double decisionRrThreshold;
    /** Per-symbol 配置覆寫（key = symbol，如 ETHUSDT） */
    private final Map<String, SymbolOverride> symbolOverrides;

    @Getter
    public static class SymbolOverride {
        private final Integer maxLayers;
        private final Double stepPercent;
        private final Double sizeMultiplier;
        private final Double takeProfitPercent;
        private final Double stopLossPercent;

        public SymbolOverride(Integer maxLayers, Double stepPercent, Double sizeMultiplier,
                              Double takeProfitPercent, Double stopLossPercent) {
            this.maxLayers = maxLayers;
            this.stepPercent = stepPercent;
            this.sizeMultiplier = sizeMultiplier;
            this.takeProfitPercent = takeProfitPercent;
            this.stopLossPercent = stopLossPercent;
        }
    }

    /** 取得指定 symbol 的有效 maxLayers（有覆寫用覆寫，否則用全域值） */
    public int getEffectiveMaxLayers(String symbol) {
        SymbolOverride ov = symbolOverrides != null ? symbolOverrides.get(symbol) : null;
        return ov != null && ov.maxLayers != null ? ov.maxLayers : maxLayers;
    }

    public double getEffectiveStepPercent(String symbol) {
        SymbolOverride ov = symbolOverrides != null ? symbolOverrides.get(symbol) : null;
        return ov != null && ov.stepPercent != null ? ov.stepPercent : stepPercent;
    }

    public double getEffectiveSizeMultiplier(String symbol) {
        SymbolOverride ov = symbolOverrides != null ? symbolOverrides.get(symbol) : null;
        return ov != null && ov.sizeMultiplier != null ? ov.sizeMultiplier : sizeMultiplier;
    }

    public double getEffectiveTakeProfitPercent(String symbol) {
        SymbolOverride ov = symbolOverrides != null ? symbolOverrides.get(symbol) : null;
        return ov != null && ov.takeProfitPercent != null ? ov.takeProfitPercent : takeProfitPercent;
    }

    public double getEffectiveStopLossPercent(String symbol) {
        SymbolOverride ov = symbolOverrides != null ? symbolOverrides.get(symbol) : null;
        return ov != null && ov.stopLossPercent != null ? ov.stopLossPercent : stopLossPercent;
    }

    public MartingaleStrategyConfig(
            @DefaultValue("5") int maxLayers,
            @DefaultValue("0.02") double stepPercent,
            @DefaultValue("100") double baseSize,
            @DefaultValue("2.0") double sizeMultiplier,
            @DefaultValue("0.01") double takeProfitPercent,
            @DefaultValue("0.30") double maxCapitalUsage,
            @DefaultValue("10000") double maxPositionSize,
            @DefaultValue("0.15") double stopLossPercent,
            @DefaultValue("480") int sessionMaxDurationMinutes,
            @DefaultValue("60") int entryIdleTimeoutMinutes,
            @DefaultValue("60000") long sessionCleanupIntervalMillis,
            @DefaultValue("5000") long stopLossCheckIntervalMillis,
            @DefaultValue("3") int maxConcurrentSessions,
            @DefaultValue("0.008") double breakevenTriggerPercent,
            @DefaultValue("0.002") double breakevenOffsetPercent,
            @DefaultValue("14") int atrPeriod,
            @DefaultValue("0.02") double atrReferencePercent,
            @DefaultValue("40") int riskScoreThreshold,
            @DefaultValue("false") boolean emaFilterEnabled,
            @DefaultValue("120") int tpDecayStartMinutes,
            @DefaultValue("60") int tpDecayIntervalMinutes,
            @DefaultValue("0.002") double tpDecayFloorPercent,
            @DefaultValue("NEVER") MartingaleDecisionEngine.DecisionMode decisionMode,
            @DefaultValue("2") int decisionMinLayers,
            @DefaultValue("3.0") double decisionRrThreshold,
            Map<String, SymbolOverride> symbolOverrides
    ) {
        this.maxLayers = maxLayers;
        this.stepPercent = stepPercent;
        this.baseSize = baseSize;
        this.sizeMultiplier = sizeMultiplier;
        this.takeProfitPercent = takeProfitPercent;
        this.maxCapitalUsage = maxCapitalUsage;
        this.maxPositionSize = maxPositionSize;
        this.stopLossPercent = stopLossPercent;
        this.sessionMaxDurationMinutes = sessionMaxDurationMinutes;
        this.entryIdleTimeoutMinutes = entryIdleTimeoutMinutes;
        this.sessionCleanupIntervalMillis = sessionCleanupIntervalMillis;
        this.stopLossCheckIntervalMillis = stopLossCheckIntervalMillis;
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.breakevenTriggerPercent = breakevenTriggerPercent;
        this.breakevenOffsetPercent = breakevenOffsetPercent;
        this.atrPeriod = atrPeriod;
        this.atrReferencePercent = atrReferencePercent;
        this.riskScoreThreshold = riskScoreThreshold;
        this.emaFilterEnabled = emaFilterEnabled;
        this.tpDecayStartMinutes = tpDecayStartMinutes;
        this.tpDecayIntervalMinutes = tpDecayIntervalMinutes;
        this.tpDecayFloorPercent = tpDecayFloorPercent;
        this.decisionMode = decisionMode;
        this.decisionMinLayers = decisionMinLayers;
        this.decisionRrThreshold = decisionRrThreshold;
        this.symbolOverrides = symbolOverrides;
    }
}
