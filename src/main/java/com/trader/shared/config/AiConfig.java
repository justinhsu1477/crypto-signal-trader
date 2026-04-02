package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 模型共用設定
 *
 * 集中管理 API Key 和預設模型，避免各模組重複設定。
 */
@Getter
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    private final String apiKey;
    private final String defaultModel;

    public AiConfig(
            String apiKey,
            @DefaultValue("gemini-2.5-flash-lite") String defaultModel
    ) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }
}
