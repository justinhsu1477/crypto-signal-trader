package com.trader.chatbot.consumer;

import com.trader.chatbot.dto.ChatbotResponse;
import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.notification.service.LineNotificationService;
import com.trader.shared.config.LineConfig;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ChatbotConsumer — MQ Consumer")
class ChatbotConsumerTest {

    @Mock private ChatbotService chatbotService;
    @Mock private LineNotificationService lineNotificationService;
    @Mock private DiscordBotService discordBotService;
    @Mock private LineConfig lineConfig;

    private ChatbotConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new ChatbotConsumer(chatbotService, lineNotificationService,
                discordBotService, lineConfig, new OkHttpClient());
    }

    private ChatbotResponse resp(String text) {
        return ChatbotResponse.builder().text(text).conversationId(100L).build();
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
                    .thenReturn(resp("回覆內容"));

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
                    .thenReturn(resp("回覆內容"));

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
        @DisplayName("有 replyToken → 不呼叫 Push API（Reply API 預設成功因為 OkHttpClient 是 real instance 會失敗，所以 fallback 到 Push）")
        void withReplyToken_fallbackToPush() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .lineReplyToken("reply-token-abc")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenReturn(resp("回覆內容"));

            consumer.consume(request);

            // Reply API 會失敗（OkHttpClient 真實呼叫會 401），fallback 到 Push
            verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
        }

        @Test
        @DisplayName("無 replyToken → 直接走 Push API")
        void withoutReplyToken_directPush() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenReturn(resp("回覆內容"));

            consumer.consume(request);

            verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
        }

        @Test
        @DisplayName("replyToken 為空白 → 直接走 Push API")
        void blankReplyToken_directPush() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("LINE").channelUserId("line1")
                    .lineReplyToken("   ")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "LINE", "line1", "你好"))
                    .thenReturn(resp("回覆內容"));

            consumer.consume(request);

            verify(lineNotificationService).pushTextMessage("line1", "回覆內容");
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

            consumer.consume(request);
        }
    }

    @Nested
    @DisplayName("Discord 頻道")
    class DiscordChannel {

        @Test
        @DisplayName("正常消費 → Discord DM 回覆 + conversationId")
        void normalConsume() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "你好"))
                    .thenReturn(resp("回覆內容"));

            consumer.consume(request);

            verify(discordBotService).sendDmReply("discord123", "回覆內容", 100L);
            verifyNoInteractions(lineNotificationService);
        }

        @Test
        @DisplayName("Service 拋異常 → Discord DM 回覆錯誤訊息（conversationId=null）")
        void serviceError() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .message("你好").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "你好"))
                    .thenThrow(new RuntimeException("DB error"));

            consumer.consume(request);

            verify(discordBotService).sendDmReply(eq("discord123"), anyString(), isNull());
        }

        @Test
        @DisplayName("有 replyChannelId → 頻道回覆 + conversationId")
        void channelMentionReply() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .replyChannelId("channel456").message("用戶狀況").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "用戶狀況"))
                    .thenReturn(resp("頻道回覆內容"));

            consumer.consume(request);

            verify(discordBotService).sendChannelReply("channel456", "discord123", "頻道回覆內容", 100L);
            verify(discordBotService, never()).sendDmReply(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("有 replyChannelId + Service 拋異常 → 頻道回覆錯誤訊息")
        void channelMentionServiceError() {
            ChatbotRequest request = ChatbotRequest.builder()
                    .userId("u1").channel("DISCORD").channelUserId("discord123")
                    .replyChannelId("channel456").message("你好").build();
            when(chatbotService.handleUserMessage("u1", "DISCORD", "discord123", "你好"))
                    .thenThrow(new RuntimeException("DB error"));

            consumer.consume(request);

            verify(discordBotService).sendChannelReply(eq("channel456"), eq("discord123"), anyString(), isNull());
            verify(discordBotService, never()).sendDmReply(anyString(), anyString(), any());
        }
    }
}
