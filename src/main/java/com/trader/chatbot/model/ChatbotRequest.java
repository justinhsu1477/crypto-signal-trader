package com.trader.chatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RabbitMQ 客服訊息請求（投遞到 chatbot.request queue）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotRequest implements Serializable {
    private String userId;
    private String lineUserId;
    private String message;
}
