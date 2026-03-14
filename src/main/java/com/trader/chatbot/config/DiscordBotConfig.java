package com.trader.chatbot.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Discord Bot（AI 客服）設定
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "discord.bot")
public class DiscordBotConfig {

    private final boolean enabled;
    private final String token;

    public DiscordBotConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token) {
        this.enabled = enabled;
        this.token = token;
    }
}
