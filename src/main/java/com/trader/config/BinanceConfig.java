package com.trader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "binance.futures")
public class BinanceConfig {

    private String baseUrl;
    private String apiKey;
    private String secretKey;
}
