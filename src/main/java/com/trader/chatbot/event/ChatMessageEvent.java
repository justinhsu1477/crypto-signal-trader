package com.trader.chatbot.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * LINE 用戶發送客服訊息事件
 *
 * 由 LineLinkingService 發布（notification 模組），
 * 由 ChatbotMessageHandler 監聽（chatbot 模組）。
 * 用 ApplicationEvent 解耦模組依賴方向。
 */
@Getter
public class ChatMessageEvent extends ApplicationEvent {

    private final String userId;
    private final String lineUserId;
    private final String text;

    public ChatMessageEvent(Object source, String userId, String lineUserId, String text) {
        super(source);
        this.userId = userId;
        this.lineUserId = lineUserId;
        this.text = text;
    }
}
