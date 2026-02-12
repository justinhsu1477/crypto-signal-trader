package com.trader.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "discord.webhook")
public class WebhookConfig {

    private final String url;
    private final boolean enabled;

    public WebhookConfig(
            String url,
            @DefaultValue("false") boolean enabled
    ) {
        this.url = url;
        this.enabled = enabled;
    }
}
