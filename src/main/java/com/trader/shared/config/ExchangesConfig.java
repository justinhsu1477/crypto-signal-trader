package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 交易所啟用設定
 *
 * 控制哪些交易所的 Bean（Adapter / StreamProvider）要載入。
 * 與 credential 無關 — per-user API Key 由 user_api_keys 表管理。
 *
 * application.yml 範例:
 * exchanges:
 *   enabled: BINANCE,BYBIT,BITGET
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "exchanges")
public class ExchangesConfig {

    private List<String> enabled = List.of("BINANCE");

    public void setEnabled(List<String> enabled) {
        this.enabled = enabled.stream()
                .map(String::toUpperCase)
                .toList();
    }

    public boolean isEnabled(String exchange) {
        return enabled.contains(exchange.toUpperCase());
    }
}
