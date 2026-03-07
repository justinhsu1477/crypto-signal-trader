package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bybit V5 線性合約 API 設定
 *
 * 對應 application.yml 中 bybit.linear.* 的設定值
 */
@Getter
@ConfigurationProperties(prefix = "bybit.linear")
public class BybitConfig {

    private final String baseUrl;
    private final String wsBaseUrl;
    private final String apiKey;
    private final String secretKey;
    private final int recvWindow;

    public BybitConfig(String baseUrl, String wsBaseUrl, String apiKey, String secretKey,
                       @DefaultValue("5000") int recvWindow) {
        this.baseUrl = baseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.recvWindow = recvWindow;
    }
}
