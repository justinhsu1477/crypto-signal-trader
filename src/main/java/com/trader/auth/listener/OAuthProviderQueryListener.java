package com.trader.auth.listener;

import com.trader.auth.repository.UserOAuthProviderRepository;
import com.trader.shared.dto.OAuthProviderInfo;
import com.trader.user.event.AdminUserDetailRequestEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 監聽 AdminUserDetailRequestEvent → 填入 OAuth 提供者資料
 *
 * 使用 @EventListener（同步），讓 AdminUserController 可立即讀取結果。
 */
@Component
@RequiredArgsConstructor
public class OAuthProviderQueryListener {

    private final UserOAuthProviderRepository oauthProviderRepository;

    @EventListener
    public void onAdminUserDetailRequest(AdminUserDetailRequestEvent event) {
        List<OAuthProviderInfo> providers = oauthProviderRepository.findByUserId(event.getUserId())
                .stream()
                .map(p -> OAuthProviderInfo.builder()
                        .provider(p.getProvider().name())
                        .displayName(p.getDisplayName())
                        .email(p.getEmail())
                        .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : null)
                        .build())
                .toList();
        event.setOAuthProviders(providers);
    }
}
