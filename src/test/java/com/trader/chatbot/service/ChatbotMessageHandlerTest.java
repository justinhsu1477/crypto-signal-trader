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

@DisplayName("ChatbotMessageHandler — Event → MQ")
class ChatbotMessageHandlerTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private ChatbotMessageHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ChatbotMessageHandler(rabbitTemplate);
    }

    @Test
    @DisplayName("收到事件 → 投遞到 MQ")
    void eventToMQ() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "lineUser1", "你好");

        handler.onChatMessage(event);

        ArgumentCaptor<ChatbotRequest> captor = ArgumentCaptor.forClass(ChatbotRequest.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_CHATBOT),
                captor.capture()
        );

        ChatbotRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo("user1");
        assertThat(request.getLineUserId()).isEqualTo("lineUser1");
        assertThat(request.getMessage()).isEqualTo("你好");
    }

    @Test
    @DisplayName("MQ 投遞失敗 → 不拋異常")
    void mqFailureHandled() {
        ChatMessageEvent event = new ChatMessageEvent(this, "user1", "lineUser1", "你好");
        doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(ChatbotRequest.class));

        // 不應拋異常
        handler.onChatMessage(event);
    }
}
