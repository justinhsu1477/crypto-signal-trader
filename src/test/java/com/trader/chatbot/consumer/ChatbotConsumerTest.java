package com.trader.chatbot.consumer;

import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
import com.trader.notification.service.LineNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ChatbotConsumer — MQ Consumer")
class ChatbotConsumerTest {

    @Mock private ChatbotService chatbotService;
    @Mock private LineNotificationService lineNotificationService;

    private ChatbotConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new ChatbotConsumer(chatbotService, lineNotificationService);
    }

    @Test
    @DisplayName("正常消費 → 呼叫 service + Push 回覆")
    void normalConsume() {
        ChatbotRequest request = ChatbotRequest.builder()
                .userId("u1").lineUserId("line1").message("你好").build();
        when(chatbotService.handleUserMessage("u1", "line1", "你好"))
                .thenReturn("回覆內容");

        consumer.consume(request);

        verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
    }

    @Test
    @DisplayName("Service 拋異常 → 回覆錯誤訊息")
    void serviceError() {
        ChatbotRequest request = ChatbotRequest.builder()
                .userId("u1").lineUserId("line1").message("你好").build();
        when(chatbotService.handleUserMessage("u1", "line1", "你好"))
                .thenThrow(new RuntimeException("DB error"));

        consumer.consume(request);

        verify(lineNotificationService).pushTextMessage(eq("line1"), anyString());
    }

    @Test
    @DisplayName("Service 和錯誤回覆都失敗 → 不拋異常")
    void bothFailureHandled() {
        ChatbotRequest request = ChatbotRequest.builder()
                .userId("u1").lineUserId("line1").message("你好").build();
        when(chatbotService.handleUserMessage("u1", "line1", "你好"))
                .thenThrow(new RuntimeException("DB error"));
        doThrow(new RuntimeException("LINE down")).when(lineNotificationService)
                .pushTextMessage(anyString(), anyString());

        // 不應拋異常（DLQ 會接手）
        consumer.consume(request);
    }
}
