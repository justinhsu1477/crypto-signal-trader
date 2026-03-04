package com.trader.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用戶帳號刪除事件
 *
 * UserService.deleteAccount() 發布此事件，
 * 其他模組（如 auth）可透過 @EventListener 監聽並清理關聯資料。
 */
@Getter
public class UserDeletedEvent extends ApplicationEvent {

    private final String userId;

    public UserDeletedEvent(Object source, String userId) {
        super(source);
        this.userId = userId;
    }
}
