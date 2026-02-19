package com.trader.trading.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多用戶跟單配置
 * 對應 application.yml 中的 multi-user 區塊
 */
@Configuration
@ConfigurationProperties(prefix = "multi-user")
@Getter
public class MultiUserConfig {

    /** 是否啟用多用戶模式（true=多用戶，false=舊系統用環境變數 API Key） */
    private boolean enabled = false;

    /** RabbitMQ 配置 */
    private RabbitMQSettings rabbitmq = new RabbitMQSettings();

    @Getter
    public static class RabbitMQSettings {
        /** 是否啟用 RabbitMQ（true=異步 MQ，false=同步執行） */
        private boolean enabled = false;

        // 未來可加：
        // private int executionWorkerCount = 10;
        // private int signalQueueBatchSize = 50;
    }

    public void setRabbitmq(RabbitMQSettings settings) {
        this.rabbitmq = settings;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
