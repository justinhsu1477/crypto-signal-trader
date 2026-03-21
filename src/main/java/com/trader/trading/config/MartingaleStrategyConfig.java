package com.trader.trading.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
            @DefaultValue("3") int maxConcurrentSessions
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
    }
}
