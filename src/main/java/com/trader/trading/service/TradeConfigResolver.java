package com.trader.trading.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.service.UserTradeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 交易參數解析器 — 根據 multi-user.enabled 決定參數來源
 *
 * multi-user.enabled = false → 全部從全局 RiskConfig 建立（單用戶模式）
 * multi-user.enabled = true  → 載入 UserTradeSettings，每個欄位 null 就 fallback 到 RiskConfig
 *
 * 使用方式：
 * ```java
 * EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);
 * config.riskPercent();       // 已解析，不用再判斷來源
 * config.isSymbolAllowed(s);  // 便利方法
 * ```
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeConfigResolver {

    private final MultiUserConfig multiUserConfig;
    private final RiskConfig riskConfig;
    private final UserTradeSettingsService userTradeSettingsService;
    private final ObjectMapper objectMapper;

    /**
     * 解析用戶的有效交易參數
     *
     * @param userId 用戶 ID（per-user 模式會查 DB）
     * @return 已解析的 EffectiveTradeConfig（所有欄位都有值）
     */
    public EffectiveTradeConfig resolve(String userId) {
        if (!multiUserConfig.isEnabled()) {
            return fromGlobal();
        }
        return fromPerUser(userId);
    }

    /**
     * 全局模式：所有參數來自 RiskConfig
     */
    private EffectiveTradeConfig fromGlobal() {
        return new EffectiveTradeConfig(
                riskConfig.getRiskPercent(),
                riskConfig.getMaxPositionUsdt(),
                riskConfig.getMaxDailyLossUsdt(),
                riskConfig.getMaxDcaPerSymbol(),
                riskConfig.getDcaRiskMultiplier(),
                riskConfig.getFixedLeverage(),
                riskConfig.getAllowedSymbols(),
                riskConfig.isDedupEnabled(),
                riskConfig.getDefaultSymbol()
        );
    }

    /**
     * Per-user 模式：每個欄位 null 就 fallback 到 RiskConfig
     */
    private EffectiveTradeConfig fromPerUser(String userId) {
        UserTradeSettings userSettings = userTradeSettingsService.getOrCreateSettings(userId);

        double riskPercent = userSettings.getRiskPercent() != null
                ? userSettings.getRiskPercent()
                : riskConfig.getRiskPercent();

        double maxPositionUsdt = userSettings.getMaxPositionSizeUsdt() != null
                ? userSettings.getMaxPositionSizeUsdt()
                : riskConfig.getMaxPositionUsdt();

        double maxDailyLossUsdt = userSettings.getDailyLossLimitUsdt() != null
                ? userSettings.getDailyLossLimitUsdt()
                : riskConfig.getMaxDailyLossUsdt();

        int maxDcaPerSymbol = userSettings.getMaxDcaLayers() != null
                ? userSettings.getMaxDcaLayers()
                : riskConfig.getMaxDcaPerSymbol();

        double dcaRiskMultiplier = userSettings.getDcaRiskMultiplier() != null
                ? userSettings.getDcaRiskMultiplier()
                : riskConfig.getDcaRiskMultiplier();

        int fixedLeverage = userSettings.getMaxLeverage() != null
                ? userSettings.getMaxLeverage()
                : riskConfig.getFixedLeverage();

        List<String> allowedSymbols = parseAllowedSymbols(userSettings.getAllowedSymbols());

        log.debug("Per-user config for {}: riskPercent={}, maxPos={}, maxDailyLoss={}, maxDca={}, leverage={}",
                userId, riskPercent, maxPositionUsdt, maxDailyLossUsdt, maxDcaPerSymbol, fixedLeverage);

        return new EffectiveTradeConfig(
                riskPercent,
                maxPositionUsdt,
                maxDailyLossUsdt,
                maxDcaPerSymbol,
                dcaRiskMultiplier,
                fixedLeverage,
                allowedSymbols,
                riskConfig.isDedupEnabled(),   // dedup 永遠用全局
                riskConfig.getDefaultSymbol()   // defaultSymbol 永遠用全局
        );
    }

    /**
     * 解析 allowedSymbols JSON string → List<String>
     * 解析失敗 fallback 到全局白名單
     */
    private List<String> parseAllowedSymbols(String json) {
        if (json == null || json.isBlank()) {
            return riskConfig.getAllowedSymbols();
        }
        try {
            List<String> symbols = objectMapper.readValue(json, new TypeReference<>() {});
            return symbols != null && !symbols.isEmpty() ? symbols : riskConfig.getAllowedSymbols();
        } catch (JsonProcessingException e) {
            log.warn("用戶 allowedSymbols JSON 解析失敗: {}，fallback 到全局白名單", json);
            return riskConfig.getAllowedSymbols();
        }
    }
}
