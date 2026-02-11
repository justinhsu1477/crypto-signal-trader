package com.trader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "discord.webhook")
public class WebhookConfig {

    /**
     * Discord Webhook URL
     * 格式: https://discord.com/api/webhooks/{id}/{token}
     */
    private String url;

    /**
     * 是否啟用 Webhook 通知（預設 false）
     */
    private boolean enabled = false;
}
