package com.trader.chatbot.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Discord Bot（AI 客服）設定
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "discord.bot")
public class DiscordBotConfig {

    private final boolean enabled;
    private final String token;
    private final List<String> adminIds;

    public DiscordBotConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token,
            List<String> adminIds) {
        this.enabled = enabled;
        this.token = token;
        this.adminIds = adminIds != null ? adminIds : List.of();
    }

    public boolean isAdmin(String discordUserId) {
        return adminIds.contains(discordUserId);
    }
}
