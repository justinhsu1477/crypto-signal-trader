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
    private final int sessionIdleTimeoutMinutes;
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
            @DefaultValue("30") int sessionIdleTimeoutMinutes,
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
        this.sessionIdleTimeoutMinutes = sessionIdleTimeoutMinutes;
        this.sessionCleanupIntervalMillis = sessionCleanupIntervalMillis;
        this.stopLossCheckIntervalMillis = stopLossCheckIntervalMillis;
        this.maxConcurrentSessions = maxConcurrentSessions;
    }
}
