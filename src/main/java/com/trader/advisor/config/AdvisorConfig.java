package com.trader.advisor.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "advisor")
public class AdvisorConfig {

    private final boolean enabled;
    private final String geminiApiKey;
    private final String geminiModel;
    private final String cronExpression;
    private final int maxResponseTokens;
    private final int recentTradesCount;
    private final double temperatureValue;

    public AdvisorConfig(
            @DefaultValue("false") boolean enabled,
            String geminiApiKey,
            @DefaultValue("gemini-2.0-flash") String geminiModel,
            @DefaultValue("0 0 * * * *") String cronExpression,
            @DefaultValue("1024") int maxResponseTokens,
            @DefaultValue("10") int recentTradesCount,
            @DefaultValue("0.7") double temperatureValue
    ) {
        this.enabled = enabled;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
        this.cronExpression = cronExpression;
        this.maxResponseTokens = maxResponseTokens;
        this.recentTradesCount = recentTradesCount;
        this.temperatureValue = temperatureValue;
    }
}
