package com.trader.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.user.dto.TradeSettingsResponse;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserTradeSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用戶交易參數服務
 *
 * 管理每位用戶的個人化風控設定（風險比例、槓桿、DCA、幣種白名單等）。
 * 新用戶首次查詢時自動建立預設值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTradeSettingsService {

    private final UserTradeSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;

    /**
     * 取得用戶設定，不存在則建立預設值
     */
    public UserTradeSettings getOrCreateSettings(String userId) {
        return settingsRepository.findById(userId)
                .orElseGet(() -> {
                    log.info("用戶 {} 首次查詢交易參數，建立預設值", userId);
                    UserTradeSettings defaults = UserTradeSettings.builder()
                            .userId(userId)
                            .riskPercent(0.20)
                            .maxLeverage(20)
                            .maxDcaLayers(3)
                            .maxPositionSizeUsdt(50000.0)
                            .allowedSymbols("[\"BTCUSDT\"]")
                            .autoSlEnabled(true)
                            .autoTpEnabled(true)
                            .build();
                    return settingsRepository.save(defaults);
                });
    }

    /**
     * 更新用戶交易參數（部分更新：只改有傳入的欄位）
     */
    @Transactional
    public UserTradeSettings updateSettings(String userId, UpdateTradeSettingsRequest request) {
        UserTradeSettings settings = getOrCreateSettings(userId);

        if (request.getRiskPercent() != null) {
            validateRange(request.getRiskPercent(), 0.01, 1.0, "riskPercent");
            settings.setRiskPercent(request.getRiskPercent());
        }
        if (request.getMaxLeverage() != null) {
            validateRange(request.getMaxLeverage(), 1, 125, "maxLeverage");
            settings.setMaxLeverage(request.getMaxLeverage());
        }
        if (request.getMaxDcaLayers() != null) {
            validateRange(request.getMaxDcaLayers(), 0, 10, "maxDcaLayers");
            settings.setMaxDcaLayers(request.getMaxDcaLayers());
        }
        if (request.getMaxPositionSizeUsdt() != null) {
            validateRange(request.getMaxPositionSizeUsdt(), 100.0, 1_000_000.0, "maxPositionSizeUsdt");
            settings.setMaxPositionSizeUsdt(request.getMaxPositionSizeUsdt());
        }
        if (request.getAllowedSymbols() != null) {
            settings.setAllowedSymbols(serializeSymbols(request.getAllowedSymbols()));
        }
        if (request.getAutoSlEnabled() != null) {
            settings.setAutoSlEnabled(request.getAutoSlEnabled());
        }
        if (request.getAutoTpEnabled() != null) {
            settings.setAutoTpEnabled(request.getAutoTpEnabled());
        }

        UserTradeSettings saved = settingsRepository.save(settings);
        log.info("用戶 {} 交易參數已更新", userId);
        return saved;
    }

    /**
     * 轉換 Entity 為 Response DTO
     */
    public TradeSettingsResponse toResponse(UserTradeSettings settings) {
        return TradeSettingsResponse.builder()
                .userId(settings.getUserId())
                .riskPercent(settings.getRiskPercent())
                .maxLeverage(settings.getMaxLeverage())
                .maxDcaLayers(settings.getMaxDcaLayers())
                .maxPositionSizeUsdt(settings.getMaxPositionSizeUsdt())
                .allowedSymbols(deserializeSymbols(settings.getAllowedSymbols()))
                .autoSlEnabled(settings.isAutoSlEnabled())
                .autoTpEnabled(settings.isAutoTpEnabled())
                .updatedAt(settings.getUpdatedAt() != null ? settings.getUpdatedAt().toString() : null)
                .build();
    }

    // ==================== private helpers ====================

    private void validateRange(double value, double min, double max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    String.format("%s 必須在 %.2f ~ %.2f 之間，收到: %.2f", field, min, max, value));
        }
    }

    private void validateRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    String.format("%s 必須在 %d ~ %d 之間，收到: %d", field, min, max, value));
        }
    }

    private String serializeSymbols(List<String> symbols) {
        try {
            return objectMapper.writeValueAsString(symbols);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> deserializeSymbols(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("allowedSymbols JSON 解析失敗: {}", json);
            return List.of();
        }
    }
}
