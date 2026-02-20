package com.trader.trading.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Per-User 個人化交易參數開關
 *
 * enabled = false（預設）：所有用戶使用全局 RiskConfig
 * enabled = true：載入 UserTradeSettings，每個欄位 null 自動 fallback 到 RiskConfig
 *
 * 用法：設定環境變數 PER_USER_SETTINGS_ENABLED=true 啟用
 */
@Configuration
@ConfigurationProperties(prefix = "per-user-settings")
@Getter
@Setter
public class PerUserSettingsConfig {

    private boolean enabled = false;
}
