package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binance Futures API 基礎設施設定
 *
 * 只保留 URL 相關設定。API credential 由 per-user user_api_keys 表管理。
 */
@Getter
@ConfigurationProperties(prefix = "binance.futures")
public class BinanceConfig {

    private final String baseUrl;
    private final String wsBaseUrl;

    public BinanceConfig(String baseUrl,
                         @org.springframework.boot.context.properties.bind.DefaultValue("wss://fstream.binance.com/ws/")
                         String wsBaseUrl) {
        this.baseUrl = baseUrl;
        this.wsBaseUrl = wsBaseUrl;
    }
}
