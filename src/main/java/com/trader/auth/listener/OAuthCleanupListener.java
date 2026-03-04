package com.trader.auth.listener;

import com.trader.auth.repository.UserOAuthProviderRepository;
import com.trader.user.event.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 監聽 UserDeletedEvent → 清理 OAuth 綁定記錄
 *
 * 遵循模組依賴規則：auth → user（auth 可依賴 user 的事件）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthCleanupListener {

    private final UserOAuthProviderRepository oauthProviderRepository;

    @TransactionalEventListener
    public void onUserDeleted(UserDeletedEvent event) {
        String userId = event.getUserId();
        oauthProviderRepository.deleteByUserId(userId);
        log.info("已清除 OAuth 綁定記錄: userId={}", userId);
    }
}
