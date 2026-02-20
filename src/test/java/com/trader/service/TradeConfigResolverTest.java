package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.PerUserSettingsConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TradeConfigResolver 單元測試
 *
 * 驗證：
 * - 全局模式：enabled=false → 所有值來自 RiskConfig
 * - Per-user 模式（全填）：enabled=true + user 全部欄位非 null → 用 user 值
 * - Per-user 模式（部分 null）：null 欄位 fallback RiskConfig
 * - allowedSymbols JSON 解析
 */
class TradeConfigResolverTest {

    private PerUserSettingsConfig perUserConfig;
    private RiskConfig riskConfig;
    private UserTradeSettingsService userTradeSettingsService;
    private ObjectMapper objectMapper;
    private TradeConfigResolver resolver;

    @BeforeEach
    void setUp() {
        perUserConfig = new PerUserSettingsConfig();
        riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT"), "BTCUSDT"
        );
        userTradeSettingsService = mock(UserTradeSettingsService.class);
        objectMapper = new ObjectMapper();
        resolver = new TradeConfigResolver(perUserConfig, riskConfig, userTradeSettingsService, objectMapper);
    }

    @Nested
    @DisplayName("全局模式 (enabled=false)")
    class GlobalMode {

        @Test
        @DisplayName("enabled=false → 所有值來自 RiskConfig")
        void allValuesFromRiskConfig() {
            perUserConfig.setEnabled(false);

            EffectiveTradeConfig config = resolver.resolve("any-user");

            assertThat(config.riskPercent()).isEqualTo(0.20);
            assertThat(config.maxPositionUsdt()).isEqualTo(50000);
            assertThat(config.maxDailyLossUsdt()).isEqualTo(2000);
            assertThat(config.maxDcaPerSymbol()).isEqualTo(3);
            assertThat(config.dcaRiskMultiplier()).isEqualTo(2.0);
            assertThat(config.fixedLeverage()).isEqualTo(20);
            assertThat(config.allowedSymbols()).containsExactly("BTCUSDT");
            assertThat(config.dedupEnabled()).isTrue();
            assertThat(config.defaultSymbol()).isEqualTo("BTCUSDT");

            // 全局模式不應查詢 UserTradeSettingsService
            verify(userTradeSettingsService, never()).getOrCreateSettings(anyString());
        }

        @Test
        @DisplayName("enabled=false → isSymbolAllowed 使用全局白名單")
        void symbolAllowedFromGlobal() {
            perUserConfig.setEnabled(false);

            EffectiveTradeConfig config = resolver.resolve("user-1");

            assertThat(config.isSymbolAllowed("BTCUSDT")).isTrue();
            assertThat(config.isSymbolAllowed("ETHUSDT")).isFalse();
        }
    }

    @Nested
    @DisplayName("Per-user 模式 (enabled=true)")
    class PerUserMode {

        @Test
        @DisplayName("用戶全部欄位非 null → 用 user 值")
        void allFieldsFromUser() {
            perUserConfig.setEnabled(true);

            UserTradeSettings userSettings = UserTradeSettings.builder()
                    .userId("user-1")
                    .riskPercent(0.10)
                    .maxPositionSizeUsdt(30000.0)
                    .dailyLossLimitUsdt(1500.0)
                    .maxDcaLayers(5)
                    .dcaRiskMultiplier(3.0)
                    .maxLeverage(50)
                    .allowedSymbols("[\"BTCUSDT\",\"ETHUSDT\"]")
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("user-1")).thenReturn(userSettings);

            EffectiveTradeConfig config = resolver.resolve("user-1");

            assertThat(config.riskPercent()).isEqualTo(0.10);
            assertThat(config.maxPositionUsdt()).isEqualTo(30000.0);
            assertThat(config.maxDailyLossUsdt()).isEqualTo(1500.0);
            assertThat(config.maxDcaPerSymbol()).isEqualTo(5);
            assertThat(config.dcaRiskMultiplier()).isEqualTo(3.0);
            assertThat(config.fixedLeverage()).isEqualTo(50);
            assertThat(config.allowedSymbols()).containsExactly("BTCUSDT", "ETHUSDT");
            // dedup 和 defaultSymbol 永遠用全局
            assertThat(config.dedupEnabled()).isTrue();
            assertThat(config.defaultSymbol()).isEqualTo("BTCUSDT");
        }

        @Test
        @DisplayName("部分欄位 null → null 欄位 fallback RiskConfig")
        void partialNullFallbackToGlobal() {
            perUserConfig.setEnabled(true);

            UserTradeSettings userSettings = UserTradeSettings.builder()
                    .userId("user-2")
                    .riskPercent(0.05)
                    .maxPositionSizeUsdt(null)     // fallback → 50000
                    .dailyLossLimitUsdt(null)      // fallback → 2000
                    .maxDcaLayers(null)             // fallback → 3
                    .dcaRiskMultiplier(null)        // fallback → 2.0
                    .maxLeverage(10)
                    .allowedSymbols(null)           // fallback → ["BTCUSDT"]
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("user-2")).thenReturn(userSettings);

            EffectiveTradeConfig config = resolver.resolve("user-2");

            assertThat(config.riskPercent()).isEqualTo(0.05);          // user 值
            assertThat(config.maxPositionUsdt()).isEqualTo(50000);     // fallback
            assertThat(config.maxDailyLossUsdt()).isEqualTo(2000);     // fallback
            assertThat(config.maxDcaPerSymbol()).isEqualTo(3);         // fallback
            assertThat(config.dcaRiskMultiplier()).isEqualTo(2.0);     // fallback
            assertThat(config.fixedLeverage()).isEqualTo(10);          // user 值
            assertThat(config.allowedSymbols()).containsExactly("BTCUSDT"); // fallback
        }

        @Test
        @DisplayName("新用戶 → getOrCreateSettings 建立預設值")
        void newUserCreatesDefaults() {
            perUserConfig.setEnabled(true);

            UserTradeSettings defaults = UserTradeSettings.builder()
                    .userId("new-user")
                    .riskPercent(0.20)
                    .maxLeverage(20)
                    .maxDcaLayers(3)
                    .maxPositionSizeUsdt(50000.0)
                    .allowedSymbols("[\"BTCUSDT\"]")
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("new-user")).thenReturn(defaults);

            EffectiveTradeConfig config = resolver.resolve("new-user");

            verify(userTradeSettingsService).getOrCreateSettings("new-user");
            assertThat(config.riskPercent()).isEqualTo(0.20);
        }

        @Test
        @DisplayName("allowedSymbols 無效 JSON → fallback 全局白名單")
        void invalidJsonFallbackGlobal() {
            perUserConfig.setEnabled(true);

            UserTradeSettings userSettings = UserTradeSettings.builder()
                    .userId("user-3")
                    .riskPercent(0.10)
                    .maxLeverage(20)
                    .allowedSymbols("not-valid-json")
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("user-3")).thenReturn(userSettings);

            EffectiveTradeConfig config = resolver.resolve("user-3");

            assertThat(config.allowedSymbols()).containsExactly("BTCUSDT"); // fallback
        }

        @Test
        @DisplayName("allowedSymbols 空陣列 JSON → fallback 全局白名單")
        void emptyArrayFallbackGlobal() {
            perUserConfig.setEnabled(true);

            UserTradeSettings userSettings = UserTradeSettings.builder()
                    .userId("user-4")
                    .riskPercent(0.10)
                    .maxLeverage(20)
                    .allowedSymbols("[]")
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("user-4")).thenReturn(userSettings);

            EffectiveTradeConfig config = resolver.resolve("user-4");

            assertThat(config.allowedSymbols()).containsExactly("BTCUSDT"); // fallback
        }

        @Test
        @DisplayName("isSymbolAllowed 使用 per-user 白名單")
        void symbolAllowedFromUser() {
            perUserConfig.setEnabled(true);

            UserTradeSettings userSettings = UserTradeSettings.builder()
                    .userId("user-5")
                    .allowedSymbols("[\"ETHUSDT\",\"SOLUSDT\"]")
                    .build();

            when(userTradeSettingsService.getOrCreateSettings("user-5")).thenReturn(userSettings);

            EffectiveTradeConfig config = resolver.resolve("user-5");

            assertThat(config.isSymbolAllowed("ETHUSDT")).isTrue();
            assertThat(config.isSymbolAllowed("SOLUSDT")).isTrue();
            assertThat(config.isSymbolAllowed("BTCUSDT")).isFalse(); // 不在 user 白名單中
        }
    }
}
