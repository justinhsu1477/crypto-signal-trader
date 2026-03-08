package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bitget V2 Mix USDT-Futures API 基礎設施設定
 *
 * 只保留 URL + recvWindow 設定。API credential（含 passphrase）由 per-user user_api_keys 表管理。
 */
@Getter
@ConfigurationProperties(prefix = "bitget.futures")
public class BitgetConfig {

    private final String baseUrl;
    private final String wsBaseUrl;
    private final int recvWindow;

    public BitgetConfig(String baseUrl, String wsBaseUrl,
                        @DefaultValue("30000") int recvWindow) {
        this.baseUrl = baseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.recvWindow = recvWindow;
    }
}
