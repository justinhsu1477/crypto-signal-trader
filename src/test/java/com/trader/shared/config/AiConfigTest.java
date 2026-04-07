package com.trader.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiConfig — AI 模型共用設定")
class AiConfigTest {

    @Test
    @DisplayName("自訂 apiKey + model → 正確設定")
    void customValues() {
        AiConfig config = new AiConfig("my-api-key", "gemini-2.5-pro");

        assertThat(config.getApiKey()).isEqualTo("my-api-key");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    @DisplayName("defaultModel 為 null → 使用預設值（由 Spring @DefaultValue 處理，單元測試傳 null 驗證 getter）")
    void nullModelPassedDirectly() {
        AiConfig config = new AiConfig("key", null);

        assertThat(config.getApiKey()).isEqualTo("key");
        assertThat(config.getDefaultModel()).isNull();
    }

    @Test
    @DisplayName("apiKey 為 null → getter 回傳 null")
    void nullApiKey() {
        AiConfig config = new AiConfig(null, "gemini-2.5-flash-lite");

        assertThat(config.getApiKey()).isNull();
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-flash-lite");
    }
}
