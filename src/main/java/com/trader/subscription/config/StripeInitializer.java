package com.trader.subscription.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe SDK 初始化
 *
 * 在應用啟動時設定 Stripe.apiKey，
 * 保持 StripeConfig 不可變（@ConfigurationProperties 慣例）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StripeInitializer {

    private final StripeConfig stripeConfig;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeConfig.getSecretKey();
        log.info("Stripe SDK 已初始化 (apiKey={}...)",
                stripeConfig.getSecretKey().length() > 12
                        ? stripeConfig.getSecretKey().substring(0, 12)
                        : "***");
    }
}
