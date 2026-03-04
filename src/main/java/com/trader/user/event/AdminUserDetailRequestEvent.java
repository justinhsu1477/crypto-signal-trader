package com.trader.user.event;

import com.trader.shared.dto.OAuthProviderInfo;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin 用戶詳情查詢事件（同步 Request-Reply 模式）
 *
 * AdminUserController 發布此事件，
 * auth 模組的 OAuthProviderQueryListener 填入 OAuth 提供者資料。
 */
@Getter
public class AdminUserDetailRequestEvent extends ApplicationEvent {

    private final String userId;
    private final AtomicReference<List<OAuthProviderInfo>> oauthProviders = new AtomicReference<>(List.of());

    public AdminUserDetailRequestEvent(Object source, String userId) {
        super(source);
        this.userId = userId;
    }

    public void setOAuthProviders(List<OAuthProviderInfo> providers) {
        this.oauthProviders.set(providers);
    }

    public List<OAuthProviderInfo> getOAuthProviders() {
        return oauthProviders.get();
    }
}
