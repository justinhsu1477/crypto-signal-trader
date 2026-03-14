package com.trader.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓撲宣告
 *
 * <pre>
 * 架構：
 *   1. Direct Exchange (notification.exchange) — 交易通知
 *       │
 *       ├── routing-key: "user"  → notification.user   (用戶通知)
 *       └── routing-key: "admin" → notification.admin   (系統/Admin 通知)
 *
 *   2. Fanout Exchange (announcement.fanout) — 公告推播
 *       │
 *       ├── announcement.discord  (Discord 全域推送)
 *       └── announcement.line     (LINE 逐用戶推送)
 *
 * DLQ（Dead Letter Queue）：
 *   訊息重試 3 次都失敗 → RepublishMessageRecoverer → DLX → notification.dlq
 *
 * 面試重點：
 *   - Direct Exchange = 精確路由（routing-key 完全匹配）
 *   - Fanout Exchange = 廣播（所有綁定的 queue 都收到，忽略 routing-key）
 *   - durable = true → RabbitMQ 重啟後 queue 還在
 *   - x-dead-letter-exchange → 失敗訊息自動轉到 DLQ
 *   - Jackson2JsonMessageConverter → 跨語言相容的 JSON 序列化
 * </pre>
 */
@Configuration
public class RabbitMQConfig {

    // ===== 常量（其他類別也會用到）=====
    public static final String EXCHANGE = "notification.exchange";
    public static final String QUEUE_USER = "notification.user";
    public static final String QUEUE_ADMIN = "notification.admin";
    public static final String ROUTING_KEY_USER = "user";
    public static final String ROUTING_KEY_ADMIN = "admin";

    // ===== 公告 Fanout Exchange =====
    public static final String ANNOUNCEMENT_EXCHANGE = "announcement.fanout";
    public static final String QUEUE_ANNOUNCEMENT_DISCORD = "announcement.discord";
    public static final String QUEUE_ANNOUNCEMENT_LINE = "announcement.line";

    // ===== Chatbot AI 客服 =====
    public static final String QUEUE_CHATBOT = "chatbot.request";
    public static final String ROUTING_KEY_CHATBOT = "chatbot";

    // DLQ 相關
    private static final String DLX_EXCHANGE = "notification.dlx";
    public static final String DLQ_QUEUE = "notification.dlq";
    private static final String DLQ_ROUTING_KEY = "dead-letter";

    // ===== Exchange =====

    @Bean
    public DirectExchange notificationExchange() {
        // durable=true: Exchange 持久化
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ===== Queues =====

    @Bean
    public Queue userQueue() {
        return QueueBuilder.durable(QUEUE_USER)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue adminQueue() {
        return QueueBuilder.durable(QUEUE_ADMIN)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        // DLQ 不需要再設 DLX（終點站）
        // TTL 7 天：過期自動丟棄，避免無限堆積佔記憶體
        return QueueBuilder.durable(DLQ_QUEUE)
                .withArgument("x-message-ttl", 7 * 24 * 60 * 60 * 1000) // 7 days in ms
                .build();
    }

    // ===== Bindings =====

    @Bean
    public Binding userBinding(Queue userQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(userQueue).to(notificationExchange).with(ROUTING_KEY_USER);
    }

    @Bean
    public Binding adminBinding(Queue adminQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(adminQueue).to(notificationExchange).with(ROUTING_KEY_ADMIN);
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    // ===== Chatbot Queue =====

    @Bean
    public Queue chatbotQueue() {
        return QueueBuilder.durable(QUEUE_CHATBOT)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding chatbotBinding(Queue chatbotQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(chatbotQueue).to(notificationExchange).with(ROUTING_KEY_CHATBOT);
    }

    // ===== Announcement Fanout Exchange =====

    /**
     * Fanout Exchange：廣播模式
     *
     * 面試重點：
     *   - Direct vs Fanout vs Topic
     *   - Direct: routing-key 精確匹配（一對一）
     *   - Fanout: 忽略 routing-key，所有綁定的 queue 都收到（一對多）
     *   - Topic: routing-key 支援萬用字元 *.# 匹配（靈活路由）
     *
     * 公告用 Fanout 因為每則公告要同時送 Discord + LINE，
     * 各 consumer 獨立消費、獨立重試、互不影響。
     */
    @Bean
    public FanoutExchange announcementFanoutExchange() {
        return new FanoutExchange(ANNOUNCEMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue announcementDiscordQueue() {
        return QueueBuilder.durable(QUEUE_ANNOUNCEMENT_DISCORD)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue announcementLineQueue() {
        return QueueBuilder.durable(QUEUE_ANNOUNCEMENT_LINE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding announcementDiscordBinding(Queue announcementDiscordQueue,
                                               FanoutExchange announcementFanoutExchange) {
        // Fanout 不需要 routing-key（面試：BindingBuilder.bind().to() 沒有 .with()）
        return BindingBuilder.bind(announcementDiscordQueue).to(announcementFanoutExchange);
    }

    @Bean
    public Binding announcementLineBinding(Queue announcementLineQueue,
                                            FanoutExchange announcementFanoutExchange) {
        return BindingBuilder.bind(announcementLineQueue).to(announcementFanoutExchange);
    }

    // ===== Message Converter =====

    @Bean
    public MessageConverter jsonMessageConverter() {
        // Jackson JSON 序列化（面試：跨語言相容，不用 Java 預設序列化）
        return new Jackson2JsonMessageConverter();
    }

    // ===== Message Recovery（重試耗盡後的處理）=====

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        // 面試重點：RepublishMessageRecoverer vs RejectAndDontRequeueRecoverer
        //   - Republish：把失敗訊息「重新發布」到 DLQ，附帶 error headers（stack trace、原 exchange/routing-key）
        //   - Reject：只是拒絕訊息，靠 queue 上的 x-dead-letter-exchange 轉到 DLQ（沒有 error 資訊）
        //   → Republish 更好排查，因為 DLQ 裡的訊息自帶失敗原因
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE, DLQ_ROUTING_KEY);
    }

    // ===== RabbitAdmin（供 DLQ 監控查詢 queue 狀態）=====

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
