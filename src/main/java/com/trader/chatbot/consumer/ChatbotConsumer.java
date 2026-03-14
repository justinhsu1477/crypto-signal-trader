package com.trader.chatbot.consumer;

import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.service.LineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 客服訊息 MQ Consumer
 *
 * 從 chatbot.request queue 消費，呼叫 ChatbotService 處理，
 * 再依據 channel 路由回覆至 LINE Push API 或 Discord DM。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotConsumer {

    private final ChatbotService chatbotService;
    private final LineNotificationService lineNotificationService;
    private final DiscordBotService discordBotService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHATBOT)
    public void consume(ChatbotRequest request) {
        String channel = request.getChannel() != null ? request.getChannel() : "LINE";
        String channelUserId = request.getChannelUserId() != null
                ? request.getChannelUserId()
                : request.getLineUserId(); // backward compat

        log.info("開始處理客服訊息: userId={} channel={}", request.getUserId(), channel);

        try {
            String response = chatbotService.handleUserMessage(
                    request.getUserId(), channel, channelUserId, request.getMessage());

            switch (channel) {
                case "DISCORD" -> discordBotService.sendDmReply(channelUserId, response);
                default -> lineNotificationService.pushTextMessage(channelUserId, response);
            }
            log.info("客服回覆已送出: userId={} channel={}", request.getUserId(), channel);
        } catch (Exception e) {
            log.error("客服訊息處理失敗: userId={} channel={} error={}",
                    request.getUserId(), channel, e.getMessage(), e);
            try {
                String errorMsg = "抱歉，處理您的訊息時發生錯誤。請稍後再試，或輸入「客服」聯繫人工客服。";
                switch (channel) {
                    case "DISCORD" -> discordBotService.sendDmReply(channelUserId, errorMsg);
                    default -> lineNotificationService.pushTextMessage(channelUserId, errorMsg);
                }
            } catch (Exception ex) {
                log.error("客服錯誤回覆也失敗: {}", ex.getMessage());
            }
        }
    }
}
