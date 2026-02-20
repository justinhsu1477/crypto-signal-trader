package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.user.dto.TradeSettingsResponse;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserTradeSettingsRepository;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserTradeSettingsServiceTest {

    private UserTradeSettingsRepository settingsRepository;
    private ObjectMapper objectMapper;
    private UserTradeSettingsService service;

    @BeforeEach
    void setUp() {
        settingsRepository = mock(UserTradeSettingsRepository.class);
        objectMapper = new ObjectMapper();
        service = new UserTradeSettingsService(settingsRepository, objectMapper);

        when(settingsRepository.save(any(UserTradeSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("getOrCreateSettings")
    class GetOrCreateSettings {

        @Test
        @DisplayName("新用戶 -> 建立預設值")
        void newUser_createsDefaults() {
            when(settingsRepository.findById("user-new")).thenReturn(Optional.empty());

            UserTradeSettings result = service.getOrCreateSettings("user-new");

            assertThat(result.getUserId()).isEqualTo("user-new");
            assertThat(result.getRiskPercent()).isEqualTo(0.20);
            assertThat(result.getMaxLeverage()).isEqualTo(20);
            assertThat(result.getMaxDcaLayers()).isEqualTo(3);
            assertThat(result.getMaxPositionSizeUsdt()).isEqualTo(50000.0);
            assertThat(result.getAllowedSymbols()).isEqualTo("[\"BTCUSDT\"]");
            assertThat(result.isAutoSlEnabled()).isTrue();
            assertThat(result.isAutoTpEnabled()).isTrue();

            verify(settingsRepository).save(any(UserTradeSettings.class));
        }

        @Test
        @DisplayName("已有用戶 -> 回傳現有設定")
        void existingUser_returnsExisting() {
            UserTradeSettings existing = UserTradeSettings.builder()
                    .userId("user-old")
                    .riskPercent(0.05)
                    .maxLeverage(10)
                    .maxDcaLayers(2)
                    .maxPositionSizeUsdt(10000.0)
                    .allowedSymbols("[\"ETHUSDT\"]")
                    .autoSlEnabled(false)
                    .autoTpEnabled(true)
                    .build();

            when(settingsRepository.findById("user-old")).thenReturn(Optional.of(existing));

            UserTradeSettings result = service.getOrCreateSettings("user-old");

            assertThat(result.getRiskPercent()).isEqualTo(0.05);
            assertThat(result.getMaxLeverage()).isEqualTo(10);

            // 不應觸發 save（不是新用戶）
            verify(settingsRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("部分更新 riskPercent -> 只改這個欄位")
        void partialUpdate_riskPercent() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(0.05);

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getRiskPercent()).isEqualTo(0.05);
            // 其他欄位保持不變
            assertThat(result.getMaxLeverage()).isEqualTo(20);
            assertThat(result.getMaxDcaLayers()).isEqualTo(3);
        }

        @Test
        @DisplayName("多欄位同時更新")
        void multiFieldUpdate() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(0.10);
            request.setMaxLeverage(50);
            request.setMaxDcaLayers(5);
            request.setAutoSlEnabled(false);
            request.setAllowedSymbols(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"));

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getRiskPercent()).isEqualTo(0.10);
            assertThat(result.getMaxLeverage()).isEqualTo(50);
            assertThat(result.getMaxDcaLayers()).isEqualTo(5);
            assertThat(result.isAutoSlEnabled()).isFalse();
            assertThat(result.getAllowedSymbols()).contains("BTCUSDT", "ETHUSDT", "SOLUSDT");
        }

        @Test
        @DisplayName("riskPercent 超出範圍 -> 拋出 IllegalArgumentException")
        void riskPercent_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(2.0); // max 1.0

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("riskPercent");
        }

        @Test
        @DisplayName("riskPercent 太小 -> 拋出例外")
        void riskPercent_tooSmall_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(0.001); // min 0.01

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("riskPercent");
        }

        @Test
        @DisplayName("maxLeverage 超出範圍 -> 拋出例外")
        void maxLeverage_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setMaxLeverage(200); // max 125

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxLeverage");
        }

        @Test
        @DisplayName("maxDcaLayers 超出範圍 -> 拋出例外")
        void maxDcaLayers_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setMaxDcaLayers(15); // max 10

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDcaLayers");
        }

        @Test
        @DisplayName("部分更新 dailyLossLimitUsdt -> 只改這個欄位")
        void partialUpdate_dailyLossLimitUsdt() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDailyLossLimitUsdt(500.0);

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getDailyLossLimitUsdt()).isEqualTo(500.0);
            // 其他欄位保持不變
            assertThat(result.getRiskPercent()).isEqualTo(0.20);
        }

        @Test
        @DisplayName("部分更新 dcaRiskMultiplier -> 只改這個欄位")
        void partialUpdate_dcaRiskMultiplier() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDcaRiskMultiplier(3.5);

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getDcaRiskMultiplier()).isEqualTo(3.5);
            // 其他欄位保持不變
            assertThat(result.getMaxLeverage()).isEqualTo(20);
        }

        @Test
        @DisplayName("dailyLossLimitUsdt 超出範圍 -> 拋出例外")
        void dailyLossLimitUsdt_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDailyLossLimitUsdt(2_000_000.0); // max 1,000,000

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dailyLossLimitUsdt");
        }

        @Test
        @DisplayName("dailyLossLimitUsdt 負數 -> 拋出例外")
        void dailyLossLimitUsdt_negative_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDailyLossLimitUsdt(-100.0);

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dailyLossLimitUsdt");
        }

        @Test
        @DisplayName("dcaRiskMultiplier 超出範圍 -> 拋出例外")
        void dcaRiskMultiplier_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDcaRiskMultiplier(15.0); // max 10.0

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dcaRiskMultiplier");
        }

        @Test
        @DisplayName("dcaRiskMultiplier 小於 1.0 -> 拋出例外")
        void dcaRiskMultiplier_tooSmall_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setDcaRiskMultiplier(0.5); // min 1.0

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dcaRiskMultiplier");
        }

        @Test
        @DisplayName("maxPositionSizeUsdt 超出範圍 -> 拋出例外")
        void maxPositionSize_outOfRange_throws() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setMaxPositionSizeUsdt(50.0); // min 100

            assertThatThrownBy(() -> service.updateSettings("user-1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPositionSizeUsdt");
        }

        @Test
        @DisplayName("邊界值（最小值）通過驗證")
        void boundaryMin_passesValidation() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(0.01);
            request.setMaxLeverage(1);
            request.setMaxDcaLayers(0);
            request.setMaxPositionSizeUsdt(100.0);

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getRiskPercent()).isEqualTo(0.01);
            assertThat(result.getMaxLeverage()).isEqualTo(1);
            assertThat(result.getMaxDcaLayers()).isEqualTo(0);
            assertThat(result.getMaxPositionSizeUsdt()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("邊界值（最大值）通過驗證")
        void boundaryMax_passesValidation() {
            UserTradeSettings existing = defaultSettings("user-1");
            when(settingsRepository.findById("user-1")).thenReturn(Optional.of(existing));

            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(1.0);
            request.setMaxLeverage(125);
            request.setMaxDcaLayers(10);
            request.setMaxPositionSizeUsdt(1_000_000.0);
            request.setDailyLossLimitUsdt(1_000_000.0);
            request.setDcaRiskMultiplier(10.0);

            UserTradeSettings result = service.updateSettings("user-1", request);

            assertThat(result.getRiskPercent()).isEqualTo(1.0);
            assertThat(result.getMaxLeverage()).isEqualTo(125);
            assertThat(result.getMaxDcaLayers()).isEqualTo(10);
            assertThat(result.getMaxPositionSizeUsdt()).isEqualTo(1_000_000.0);
            assertThat(result.getDailyLossLimitUsdt()).isEqualTo(1_000_000.0);
            assertThat(result.getDcaRiskMultiplier()).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("正常轉換 -> DTO 欄位正確（含 dailyLossLimitUsdt + dcaRiskMultiplier）")
        void normalConversion() {
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("user-1")
                    .riskPercent(0.15)
                    .maxLeverage(25)
                    .maxDcaLayers(3)
                    .maxPositionSizeUsdt(30000.0)
                    .dailyLossLimitUsdt(1500.0)
                    .dcaRiskMultiplier(2.5)
                    .allowedSymbols("[\"BTCUSDT\",\"ETHUSDT\"]")
                    .autoSlEnabled(true)
                    .autoTpEnabled(false)
                    .build();

            TradeSettingsResponse response = service.toResponse(settings);

            assertThat(response.getUserId()).isEqualTo("user-1");
            assertThat(response.getRiskPercent()).isEqualTo(0.15);
            assertThat(response.getMaxLeverage()).isEqualTo(25);
            assertThat(response.getMaxDcaLayers()).isEqualTo(3);
            assertThat(response.getMaxPositionSizeUsdt()).isEqualTo(30000.0);
            assertThat(response.getDailyLossLimitUsdt()).isEqualTo(1500.0);
            assertThat(response.getDcaRiskMultiplier()).isEqualTo(2.5);
            assertThat(response.getAllowedSymbols()).containsExactly("BTCUSDT", "ETHUSDT");
            assertThat(response.isAutoSlEnabled()).isTrue();
            assertThat(response.isAutoTpEnabled()).isFalse();
        }

        @Test
        @DisplayName("allowedSymbols 為 null -> 回傳空 List")
        void nullSymbols_returnsEmptyList() {
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("user-1")
                    .allowedSymbols(null)
                    .build();

            TradeSettingsResponse response = service.toResponse(settings);

            assertThat(response.getAllowedSymbols()).isEmpty();
        }

        @Test
        @DisplayName("allowedSymbols 為空字串 -> 回傳空 List")
        void emptySymbols_returnsEmptyList() {
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("user-1")
                    .allowedSymbols("")
                    .build();

            TradeSettingsResponse response = service.toResponse(settings);

            assertThat(response.getAllowedSymbols()).isEmpty();
        }

        @Test
        @DisplayName("allowedSymbols 為無效 JSON -> 回傳空 List (不崩潰)")
        void invalidJsonSymbols_returnsEmptyList() {
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("user-1")
                    .allowedSymbols("not-valid-json")
                    .build();

            TradeSettingsResponse response = service.toResponse(settings);

            assertThat(response.getAllowedSymbols()).isEmpty();
        }
    }

    // ==================== Helper ====================

    private UserTradeSettings defaultSettings(String userId) {
        return UserTradeSettings.builder()
                .userId(userId)
                .riskPercent(0.20)
                .maxLeverage(20)
                .maxDcaLayers(3)
                .maxPositionSizeUsdt(50000.0)
                .allowedSymbols("[\"BTCUSDT\"]")
                .autoSlEnabled(true)
                .autoTpEnabled(true)
                .build();
    }
}
