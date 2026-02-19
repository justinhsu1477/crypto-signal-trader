package com.trader.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 隊列配置（僅在 rabbitmq.enabled=true 時啟用）
 *
 * 佇列設計：
 * 1. signal-queue：接收 Discord 信號（1 個消費者）
 *    - 處理邏輯：查詢所有用戶，為每個用戶發送 ExecutionTask 到 execution-queue
 *
 * 2. execution-queue：執行單個用戶的交易（10 個並行消費者）
 *    - 處理邏輯：BinanceFuturesService.executeSignal(userId, signal)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "multi-user.rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {

    // ==================== Queue Names ====================
    public static final String SIGNAL_QUEUE = "signal-queue";
    public static final String EXECUTION_QUEUE = "execution-queue";
    public static final String SIGNAL_EXCHANGE = "trading.exchange";
    public static final String SIGNAL_ROUTING_KEY = "signal.broadcast";
    public static final String EXECUTION_ROUTING_KEY = "execution.user";

    // ==================== Queue 定義 ====================

    /**
     * Signal 佇列：接收 Discord 信號（1 個消費者，無 prefetch limit）
     * 用於廣播給所有用戶
     */
    @Bean
    public Queue signalQueue() {
        return QueueBuilder.durable(SIGNAL_QUEUE)
                .withArgument("x-message-ttl", 300000)  // 5 分鐘 TTL
                .build();
    }

    /**
     * Execution 佇列：執行個別用戶的交易（10 個並行消費者）
     * 每個消費者一次只處理 1 個任務，完成後才拿下一個
     */
    @Bean
    public Queue executionQueue() {
        return QueueBuilder.durable(EXECUTION_QUEUE)
                .withArgument("x-message-ttl", 600000)  // 10 分鐘 TTL
                .build();
    }

    // ==================== Exchange 定義 ====================

    @Bean
    public DirectExchange tradingExchange() {
        return new DirectExchange(SIGNAL_EXCHANGE, true, false);
    }

    // ==================== Binding 定義 ====================

    @Bean
    public Binding signalBinding(Queue signalQueue, DirectExchange tradingExchange) {
        return BindingBuilder.bind(signalQueue)
                .to(tradingExchange)
                .with(SIGNAL_ROUTING_KEY);
    }

    @Bean
    public Binding executionBinding(Queue executionQueue, DirectExchange tradingExchange) {
        return BindingBuilder.bind(executionQueue)
                .to(tradingExchange)
                .with(EXECUTION_ROUTING_KEY);
    }
}
