package com.trader.chatbot.consumer;

import com.trader.chatbot.model.ChatbotRequest;
import com.trader.chatbot.service.ChatbotService;
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
 * 再透過 LINE Push API 回覆用戶。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotConsumer {

    private final ChatbotService chatbotService;
    private final LineNotificationService lineNotificationService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHATBOT)
    public void consume(ChatbotRequest request) {
        log.info("開始處理客服訊息: userId={} message={}",
                request.getUserId(),
                request.getMessage().length() > 30
                        ? request.getMessage().substring(0, 30) + "..."
                        : request.getMessage());

        try {
            String response = chatbotService.handleUserMessage(
                    request.getUserId(),
                    request.getLineUserId(),
                    request.getMessage()
            );

            lineNotificationService.pushTextMessage(request.getLineUserId(), response);
            log.info("客服回覆已送出: userId={}", request.getUserId());
        } catch (Exception e) {
            log.error("客服訊息處理失敗: userId={} error={}", request.getUserId(), e.getMessage(), e);
            // 嘗試回覆錯誤訊息
            try {
                lineNotificationService.pushTextMessage(request.getLineUserId(),
                        "抱歉，處理您的訊息時發生錯誤。請稍後再試，或輸入「客服」聯繫人工客服。");
            } catch (Exception ex) {
                log.error("客服錯誤回覆也失敗: {}", ex.getMessage());
            }
        }
    }
}
