package com.trader.chatbot.service;

import com.trader.chatbot.event.ChatMessageEvent;
import com.trader.chatbot.model.ChatbotRequest;
import com.trader.notification.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 客服訊息事件處理器（Event -> MQ Producer）
 *
 * 監聯 LineLinkingService / DiscordBotListener 發布的 ChatMessageEvent，
 * 將請求投遞到 RabbitMQ chatbot.request queue。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotMessageHandler {

    private final RabbitTemplate rabbitTemplate;

    @EventListener
    public void onChatMessage(ChatMessageEvent event) {
        String channel = event.getChannel() != null ? event.getChannel() : "LINE";
        String channelUserId = event.getChannelUserId() != null
                ? event.getChannelUserId()
                : event.getLineUserId();

        log.info("收到客服訊息事件: userId={} channel={} channelUserId={}",
                event.getUserId(), channel, channelUserId);

        ChatbotRequest request = ChatbotRequest.builder()
                .userId(event.getUserId())
                .channel(channel)
                .channelUserId(channelUserId)
                .replyChannelId(event.getReplyChannelId())
                .lineUserId(event.getLineUserId()) // backward compat
                .message(event.getText())
                .build();

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY_CHATBOT,
                    request
            );
            log.debug("客服請求已投遞到 MQ: userId={} channel={}", event.getUserId(), channel);
        } catch (Exception e) {
            log.error("客服請求投遞 MQ 失敗: userId={} error={}", event.getUserId(), e.getMessage());
        }
    }
}
