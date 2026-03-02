package com.trader.notification.service;

import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.model.NotificationCategory;
import com.trader.notification.model.NotificationMessage;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CompositeNotificationService 單元測試（Producer）
 *
 * 驗證：
 * 1. 正常路徑：呼叫 RabbitTemplate.convertAndSend() 帶正確 routing key
 * 2. 降級路徑：MQ 掛了時 fallback 到直接呼叫 Discord/LINE
 * 3. Cache 方法：直接轉發（不經 MQ）
 */
class CompositeNotificationServiceTest {

    private DiscordWebhookService discordService;
    private LineNotificationService lineService;
    private RabbitTemplate rabbitTemplate;
    private CompositeNotificationService composite;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordWebhookService.class);
        lineService = mock(LineNotificationService.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        composite = new CompositeNotificationService(discordService, lineService, rabbitTemplate);
    }

    // ===== 正常路徑：發到 MQ =====

    @Test
    @DisplayName("sendNotification → 發到 ADMIN queue")
    void sendNotificationPublishesToAdminQueue() {
        composite.sendNotification("標題", "內容", NotificationService.COLOR_GREEN);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY_ADMIN), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.getType()).isEqualTo(NotificationMessage.NotificationType.SYSTEM);
        assertThat(msg.getTitle()).isEqualTo("標題");
        assertThat(msg.getMessage()).isEqualTo("內容");
        assertThat(msg.getColor()).isEqualTo(NotificationService.COLOR_GREEN);
        // sendNotification 不帶 userId 和 displayName
        assertThat(msg.getUserId()).isNull();
        assertThat(msg.getDisplayName()).isNull();

        // 正常路徑不應直接呼叫 Discord/LINE
        verifyNoInteractions(discordService, lineService);
    }

    @Test
    @DisplayName("sendNotificationToUser → 發到 USER queue")
    void sendNotificationToUserPublishesToUserQueue() {
        composite.sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY_USER), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.getType()).isEqualTo(NotificationMessage.NotificationType.USER);
        assertThat(msg.getUserId()).isEqualTo("user-1");

        verifyNoInteractions(discordService, lineService);
    }

    @Test
    @DisplayName("sendNotificationToUser (帶分類) → 發到 USER queue 並帶 category")
    void sendNotificationToUserWithCategoryPublishesToUserQueue() {
        composite.sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY_USER), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.getCategory()).isEqualTo(NotificationCategory.TRADE_EXECUTION);

        verifyNoInteractions(discordService, lineService);
    }

    @Test
    @DisplayName("sendNotificationToAdmins → 發到 ADMIN queue")
    void sendNotificationToAdminsPublishesToAdminQueue() {
        composite.sendNotificationToAdmins("標題", "內容", NotificationService.COLOR_YELLOW);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY_ADMIN), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.getType()).isEqualTo(NotificationMessage.NotificationType.ADMIN);
        assertThat(msg.getDisplayName()).isNull();

        verifyNoInteractions(discordService, lineService);
    }

    @Test
    @DisplayName("sendNotificationToAdmins (帶 displayName) → 發到 ADMIN queue 並帶 displayName")
    void sendNotificationToAdminsWithDisplayNamePublishesToAdminQueue() {
        composite.sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY_ADMIN), captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.getDisplayName()).isEqualTo("Alice");

        verifyNoInteractions(discordService, lineService);
    }

    // ===== 降級路徑：MQ 掛了 → fallback =====

    @Test
    @DisplayName("MQ 失敗 → sendNotification fallback 到直接呼叫 Discord/LINE")
    void sendNotificationFallsBackWhenMqFails() {
        doThrow(new AmqpException("Connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(NotificationMessage.class));

        composite.sendNotification("標題", "內容", NotificationService.COLOR_GREEN);

        // fallback 直接呼叫
        verify(discordService).sendNotification("標題", "內容", NotificationService.COLOR_GREEN);
        verify(lineService).sendNotification("標題", "內容", NotificationService.COLOR_GREEN);
    }

    @Test
    @DisplayName("MQ 失敗 → sendNotificationToUser fallback 到直接呼叫")
    void sendNotificationToUserFallsBackWhenMqFails() {
        doThrow(new AmqpException("Connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(NotificationMessage.class));

        composite.sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);

        verify(discordService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);
        verify(lineService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);
    }

    @Test
    @DisplayName("MQ 失敗 → sendNotificationToAdmins fallback 到直接呼叫")
    void sendNotificationToAdminsFallsBackWhenMqFails() {
        doThrow(new AmqpException("Connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(NotificationMessage.class));

        composite.sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);

        verify(discordService).sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);
        verify(lineService).sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);
    }

    // ===== Cache 操作：直接轉發 =====

    @Test
    @DisplayName("evictUserCache 直接委派（不經 MQ）")
    void evictUserCacheDelegatesToBoth() {
        composite.evictUserCache("user-1");

        verify(discordService).evictUserCache("user-1");
        verify(lineService).evictUserCache("user-1");
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("evictAllCache 直接委派（不經 MQ）")
    void evictAllCacheDelegatesToBoth() {
        composite.evictAllCache();

        verify(discordService).evictAllCache();
        verify(lineService).evictAllCache();
        verifyNoInteractions(rabbitTemplate);
    }
}
