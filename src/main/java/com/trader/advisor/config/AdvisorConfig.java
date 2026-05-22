package com.trader.advisor.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "advisor")
public class AdvisorConfig {

    private final boolean enabled;
    private final String cronExpression;
    private final int maxResponseTokens;
    private final int recentTradesCount;
    private final double temperatureValue;
    private final boolean scoringEnabled;

    public AdvisorConfig(
            @DefaultValue("false") boolean enabled,
            // 每天台北 09:00 (亞洲盤前) + 21:00 (美盤開始) 跑一次
            @DefaultValue("0 0 9,21 * * *") String cronExpression,
            @DefaultValue("1024") int maxResponseTokens,
            @DefaultValue("10") int recentTradesCount,
            @DefaultValue("0.7") double temperatureValue,
            @DefaultValue("false") boolean scoringEnabled
    ) {
        this.enabled = enabled;
        this.cronExpression = cronExpression;
        this.maxResponseTokens = maxResponseTokens;
        this.recentTradesCount = recentTradesCount;
        this.temperatureValue = temperatureValue;
        this.scoringEnabled = scoringEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public int getMaxResponseTokens() {
        return maxResponseTokens;
    }

    public int getRecentTradesCount() {
        return recentTradesCount;
    }

    public double getTemperatureValue() {
        return temperatureValue;
    }

    public boolean isScoringEnabled() {
        return scoringEnabled;
    }
}
