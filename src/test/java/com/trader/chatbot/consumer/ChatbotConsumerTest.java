package com.trader.chatbot.consumer;

import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.notification.service.LineNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    @Mock private DiscordBotService discordBotService;

    private ChatbotConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new ChatbotConsumer(chatbotService, lineNotificationService, discordBotService);
    }

    @Nested
    @DisplayName("LINE 頻道")
    class LineChannel {

        @Test
        @DisplayName("正常消費 → 呼叫 service + LINE Push 回覆")
        void normalConsume() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .lineUserId("line1").message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenReturn("回覆內容");

            consumer.consume(request);

            verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
            verifyNoInteractions(discordBotService);
        }

        @Test
        @DisplayName("backward compat — 無 channel 欄位預設 LINE")
        void backwardCompat() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").lineUserId("line1").message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenReturn("回覆內容");

            consumer.consume(request);

            verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
        }

        @Test
        @DisplayName("Service 拋異常 → 回覆錯誤訊息")
        void serviceError() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .lineUserId("line1").message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenThrow(new RuntimeException("DB error"));

            consumer.consume(request);

            verify(lineNotificationService).pushTextMessage(eq("line1"), anyString());
        }

        @Test
        @DisplayName("Service 和錯誤回覆都失敗 → 不拋異常")
        void bothFailureHandled() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .lineUserId("line1").message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenThrow(new RuntimeException("DB error"));
            doThrow(new RuntimeException("LINE down")).when(lineNotificationService)
                    .pushTextMessage(anyString(), anyString());

            // 不應拋異常（DLQ 會接手）
            consumer.consume(request);
        }
    }

    @Nested
    @DisplayName("Discord 頻道")
    class DiscordChannel {

        @Test
        @DisplayName("正常消費 → 呼叫 service + Discord DM 回覆")
        void normalConsume() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "你好"))
                    .thenReturn("回覆內容");

            consumer.consume(request);

            verify(discordBotService).sendDmReply("discord123", "回覆內容");
            verifyNoInteractions(lineNotificationService);
        }

        @Test
        @DisplayName("Service 拋異常 → Discord 回覆錯誤訊息")
        void serviceError() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "你好"))
                    .thenThrow(new RuntimeException("DB error"));

            consumer.consume(request);

            verify(discordBotService).sendDmReply(eq("discord123"), anyString());
        }
    }
}
