package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bybit V5 線性合約 API 基礎設施設定
 *
 * 只保留 URL + recvWindow 設定。API credential 由 per-user user_api_keys 表管理。
 */
@Getter
@ConfigurationProperties(prefix = "bybit.linear")
public class BybitConfig {

    private final String baseUrl;
    private final String wsBaseUrl;
    private final int recvWindow;

    public BybitConfig(String baseUrl, String wsBaseUrl,
                       @DefaultValue("5000") int recvWindow) {
        this.baseUrl = baseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.recvWindow = recvWindow;
    }
}
