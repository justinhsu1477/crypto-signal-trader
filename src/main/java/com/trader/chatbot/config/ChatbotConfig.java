package com.trader.chatbot.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 客服 Agent 設定
 *
 * 由 @ConfigurationPropertiesScan 註冊，不需要 @Configuration
 */
@Getter
@ConfigurationProperties(prefix = "chatbot")
public class ChatbotConfig {

    private final boolean enabled;
    private final int maxResponseTokens;
    private final double temperature;
    private final int maxConversationTurns;
    private final int conversationTtlMinutes;
    private final int rateLimitPerMinute;
    private final int rateLimitPerDay;
    private final boolean aiClassificationEnabled;

    public ChatbotConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("512") int maxResponseTokens,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("10") int maxConversationTurns,
            @DefaultValue("120") int conversationTtlMinutes,
            @DefaultValue("5") int rateLimitPerMinute,
            @DefaultValue("50") int rateLimitPerDay,
            @DefaultValue("true") boolean aiClassificationEnabled
    ) {
        this.enabled = enabled;
        this.maxResponseTokens = maxResponseTokens;
        this.temperature = temperature;
        this.maxConversationTurns = maxConversationTurns;
        this.conversationTtlMinutes = conversationTtlMinutes;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.rateLimitPerDay = rateLimitPerDay;
        this.aiClassificationEnabled = aiClassificationEnabled;
    }
}
