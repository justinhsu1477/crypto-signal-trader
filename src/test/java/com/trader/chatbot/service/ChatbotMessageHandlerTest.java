package com.trader.chatbot.service;

import com.trader.chatbot.event.ChatMessageEvent;
import com.trader.chatbot.model.ChatbotRequest;
import com.trader.notification.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ChatbotMessageHandler — Event -> MQ")
class ChatbotMessageHandlerTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private ChatbotMessageHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ChatbotMessageHandler(rabbitTemplate);
    }

    @Test
    @DisplayName("收到 LINE 事件 → 投遞到 MQ（含 channel 欄位）")
    void lineEventToMQ() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "LINE", "lineUser1", "你好");

        handler.onChatMessage(event);

        ArgumentCaptor<ChatbotRequest> captor = ArgumentCaptor.forClass(ChatbotRequest.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_CHATBOT),
                captor.capture()
        );

        ChatbotRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo("user1");
        assertThat(request.getChannel()).isEqualTo("LINE");
        assertThat(request.getChannelUserId()).isEqualTo("lineUser1");
        assertThat(request.getLineUserId()).isEqualTo("lineUser1");
        assertThat(request.getMessage()).isEqualTo("你好");
    }

    @Test
    @DisplayName("收到 Discord 事件 → 投遞到 MQ")
    void discordEventToMQ() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "DISCORD", "discord123", "查餘額");

        handler.onChatMessage(event);

        ArgumentCaptor<ChatbotRequest> captor = ArgumentCaptor.forClass(ChatbotRequest.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_CHATBOT),
                captor.capture()
        );

        ChatbotRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo("user1");
        assertThat(request.getChannel()).isEqualTo("DISCORD");
        assertThat(request.getChannelUserId()).isEqualTo("discord123");
        assertThat(request.getLineUserId()).isNull();
        assertThat(request.getMessage()).isEqualTo("查餘額");
    }

    @Test
    @DisplayName("backward compat — 舊格式事件仍可處理")
    @SuppressWarnings("deprecation")
    void backwardCompatEvent() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "lineUser1", "你好");

        handler.onChatMessage(event);

        ArgumentCaptor<ChatbotRequest> captor = ArgumentCaptor.forClass(ChatbotRequest.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_CHATBOT),
                captor.capture()
        );

        ChatbotRequest request = captor.getValue();
        assertThat(request.getChannel()).isEqualTo("LINE");
        assertThat(request.getChannelUserId()).isEqualTo("lineUser1");
    }

    @Test
    @DisplayName("Discord 頻道 @mention 事件 → 帶 replyChannelId 投遞到 MQ")
    void discordChannelMentionEvent() {
        ChatMessageEvent event = new ChatMessageEvent(this,
                "ADMIN", "DISCORD", "discord123", "查用戶", "channel456");

        handler.onChatMessage(event);

        ArgumentCaptor<ChatbotRequest> captor = ArgumentCaptor.forClass(ChatbotRequest.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_CHATBOT),
                captor.capture()
        );

        ChatbotRequest request = captor.getValue();
        assertThat(request.getChannel()).isEqualTo("DISCORD");
        assertThat(request.getReplyChannelId()).isEqualTo("channel456");
        assertThat(request.getChannelUserId()).isEqualTo("discord123");
    }

    @Test
    @DisplayName("MQ 投遞失敗 → 不拋異常")
    void mqFailureHandled() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "LINE", "lineUser1", "你好");
        doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(ChatbotRequest.class));

        // 不應拋異常
        handler.onChatMessage(event);
    }
}
