package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "discord.webhook")
public class WebhookConfig {

    private final String url;
    private final boolean enabled;
    private final PerUserSettings perUser;

    public WebhookConfig(
            String url,
            @DefaultValue("false") boolean enabled,
            PerUserSettings perUser
    ) {
        this.url = url;
        this.enabled = enabled;
        this.perUser = perUser;
    }

    @Getter
    public static class PerUserSettings {
        private final boolean enabled;
        private final boolean fallbackToGlobal;

        public PerUserSettings(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("true") boolean fallbackToGlobal
        ) {
            this.enabled = enabled;
            this.fallbackToGlobal = fallbackToGlobal;
        }
    }
}
