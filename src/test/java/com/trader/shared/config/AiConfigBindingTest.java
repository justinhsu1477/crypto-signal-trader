package com.trader.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiConfig @ConfigurationProperties 綁定測試
 *
 * 使用 Spring Binder 驗證屬性綁定，不需要啟動完整 ApplicationContext。
 * 補充純 unit test 無法覆蓋的 @DefaultValue 行為。
 */
@DisplayName("AiConfig — Spring @ConfigurationProperties 綁定")
class AiConfigBindingTest {

    @Test
    @DisplayName("自訂 ai.api-key + ai.default-model → 正確綁定")
    void customPropertyBinding() {
        AiConfig config = bind(Map.of(
                "ai.api-key", "test-key-from-property",
                "ai.default-model", "gemini-2.5-pro"
        ));

        assertThat(config.getApiKey()).isEqualTo("test-key-from-property");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    @DisplayName("未設定 ai.default-model → @DefaultValue 生效 → gemini-2.5-flash-lite")
    void defaultModelFallback() {
        AiConfig config = bind(Map.of(
                "ai.api-key", "some-key"
        ));

        assertThat(config.getApiKey()).isEqualTo("some-key");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-flash-lite");
    }

    @Test
    @DisplayName("ai.api-key 為空字串 → getter 回傳空字串")
    void emptyApiKey() {
        AiConfig config = bind(Map.of(
                "ai.api-key", "",
                "ai.default-model", "gemini-2.5-flash-lite"
        ));

        assertThat(config.getApiKey()).isEmpty();
    }

    @Test
    @DisplayName("兩者都未設定 → apiKey 為 null，model 為預設值")
    void nothingSet() {
        AiConfig config = bind(Map.of());

        assertThat(config.getApiKey()).isNull();
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-flash-lite");
    }

    @Test
    @DisplayName("kebab-case 屬性名稱（api-key）正確綁定到 camelCase 欄位（apiKey）")
    void kebabCaseToCamelCase() {
        AiConfig config = bind(Map.of(
                "ai.api-key", "kebab-test"
        ));

        assertThat(config.getApiKey()).isEqualTo("kebab-test");
    }

    /**
     * 使用 Spring Binder 模擬 @ConfigurationProperties 綁定
     */
    private AiConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("ai", AiConfig.class)
                .orElseGet(() -> new AiConfig(null, "gemini-2.5-flash-lite"));
    }
}
