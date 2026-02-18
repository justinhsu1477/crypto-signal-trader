package com.trader.subscription.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stripe 設定
 *
 * 對應 application.yml:
 * stripe:
 *   secret-key: sk_test_...
 *   webhook-secret: whsec_...
 */
@Getter
@ConfigurationProperties(prefix = "stripe")
public class StripeConfig {

    private final String secretKey;
    private final String webhookSecret;

    public StripeConfig(
            @DefaultValue("sk_test_placeholder") String secretKey,
            @DefaultValue("whsec_placeholder") String webhookSecret) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
    }
}
