package com.trader.chatbot.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * AI 客服 Agent 設定
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "chatbot")
public class ChatbotConfig {

    private final boolean enabled;
    private final int maxResponseTokens;
    private final double temperature;
    private final int maxConversationTurns;
    private final int conversationTtlMinutes;
    private final String geminiModel;
    private final int rateLimitPerMinute;
    private final int rateLimitPerDay;

    public ChatbotConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("512") int maxResponseTokens,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("10") int maxConversationTurns,
            @DefaultValue("30") int conversationTtlMinutes,
            @DefaultValue("gemini-3.0-pro") String geminiModel,
            @DefaultValue("5") int rateLimitPerMinute,
            @DefaultValue("50") int rateLimitPerDay
    ) {
        this.enabled = enabled;
        this.maxResponseTokens = maxResponseTokens;
        this.temperature = temperature;
        this.maxConversationTurns = maxConversationTurns;
        this.conversationTtlMinutes = conversationTtlMinutes;
        this.geminiModel = geminiModel;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.rateLimitPerDay = rateLimitPerDay;
    }
}
