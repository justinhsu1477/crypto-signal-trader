package com.trader.chatbot.consumer;

import com.trader.chatbot.dto.ChatbotResponse;
import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.service.LineNotificationService;
import com.trader.shared.config.LineConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 客服訊息 MQ Consumer
 *
 * 從 chatbot.request queue 消費，呼叫 ChatbotService 處理，
 * 再依據 channel 路由回覆：
 * - LINE：優先用 Reply API（免費），失敗時 fallback 到 Push API
 * - Discord：DM 或頻道回覆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotConsumer {

    private final ChatbotService chatbotService;
    private final LineNotificationService lineNotificationService;
    private final DiscordBotService discordBotService;
    private final LineConfig lineConfig;
    private final OkHttpClient httpClient;

    private static final String LINE_REPLY_API_URL = "https://api.line.me/v2/bot/message/reply";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHATBOT)
    public void consume(ChatbotRequest request) {
        String channel = request.getChannel() != null ? request.getChannel() : "LINE";
        String channelUserId = request.getChannelUserId() != null
                ? request.getChannelUserId()
                : request.getLineUserId(); // backward compat

        log.info("開始處理客服訊息: userId={} channel={}", request.getUserId(), channel);

        try {
            ChatbotResponse chatbotResponse = chatbotService.handleUserMessage(
                    request.getUserId(), channel, channelUserId, request.getMessage());

            sendReply(channel, channelUserId, request.getReplyChannelId(),
                    request.getLineReplyToken(),
                    chatbotResponse.getText(), chatbotResponse.getConversationId());
            log.info("客服回覆已送出: userId={} channel={} replyChannelId={} convId={}",
                    request.getUserId(), channel, request.getReplyChannelId(),
                    chatbotResponse.getConversationId());
        } catch (Exception e) {
            log.error("客服訊息處理失敗: userId={} channel={} error={}",
                    request.getUserId(), channel, e.getMessage(), e);
            try {
                String errorMsg = "抱歉，處理您的訊息時發生錯誤。請稍後再試，或輸入「客服」聯繫人工客服。";
                sendReply(channel, channelUserId, request.getReplyChannelId(),
                        null, errorMsg, null);
            } catch (Exception ex) {
                log.error("客服錯誤回覆也失敗: {}", ex.getMessage());
            }
        }
    }

    /**
     * 依據 channel 路由回覆
     * - LINE：優先 Reply API（免費不限量），30 秒過期則 fallback 到 Push API
     * - DISCORD + replyChannelId → 頻道回覆
     * - DISCORD + null → DM 回覆
     */
    private void sendReply(String channel, String channelUserId, String replyChannelId,
                           String lineReplyToken, String text, Long conversationId) {
        switch (channel) {
            case "DISCORD" -> {
                if (replyChannelId != null && !replyChannelId.isBlank()) {
                    discordBotService.sendChannelReply(replyChannelId, channelUserId, text, conversationId);
                } else {
                    discordBotService.sendDmReply(channelUserId, text, conversationId);
                }
            }
            default -> sendLineReply(channelUserId, lineReplyToken, text);
        }
    }

    /**
     * LINE 回覆：優先 Reply API（免費），失敗則 fallback 到 Push API
     */
    private void sendLineReply(String lineUserId, String replyToken, String text) {
        if (replyToken != null && !replyToken.isBlank()) {
            boolean replySuccess = sendLineReplyApi(replyToken, text);
            if (replySuccess) {
                log.debug("LINE Reply API 回覆成功: lineUserId={}", lineUserId);
                return;
            }
            log.info("LINE Reply API 失敗（token 可能已過期），fallback 到 Push API: lineUserId={}", lineUserId);
        }
        lineNotificationService.pushTextMessage(lineUserId, text);
    }

    /**
     * 呼叫 LINE Reply API（同步，因為需要知道是否成功以決定 fallback）
     */
    private boolean sendLineReplyApi(String replyToken, String text) {
        String escapedText = escapeJson(text);

        String json = """
                {
                  "replyToken": "%s",
                  "messages": [{"type": "text", "text": "%s"}]
                }""".formatted(escapeJson(replyToken), escapedText);

        Request request = new Request.Builder()
                .url(LINE_REPLY_API_URL)
                .addHeader("Authorization", "Bearer " + lineConfig.getChannelAccessToken())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            log.warn("LINE Reply API 回應異常: HTTP {}", response.code());
            return false;
        } catch (IOException e) {
            log.warn("LINE Reply API 呼叫失敗: {}", e.getMessage());
            return false;
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
