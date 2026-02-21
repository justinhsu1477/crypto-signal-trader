package com.trader.trading.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多用戶模式配置
 * 對應 application.yml 中的 multi-user 區塊
 *
 * enabled = false（預設）：單用戶模式
 *   - DB 查詢不加 userId 過濾
 *   - 交易參數使用全局 RiskConfig
 *   - WebSocket 單連線
 *
 * enabled = true：多用戶模式
 *   - DB 查詢按 userId 隔離
 *   - 交易參數使用 UserTradeSettings（null fallback RiskConfig）
 *   - WebSocket 多連線（每用戶一條）
 */
@Configuration
@ConfigurationProperties(prefix = "multi-user")
@Getter
@Setter
public class MultiUserConfig {

    /** 是否啟用多用戶模式 */
    private boolean enabled = false;
}
