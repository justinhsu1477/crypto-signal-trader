package com.trader.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "binance.futures")
public class BinanceConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public BinanceConfig(String baseUrl, String apiKey, String secretKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }
}
