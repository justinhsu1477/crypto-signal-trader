package com.trader.shared.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全工具類
 *
 * 從 SecurityContext 取得當前登入用戶的 ID。
 * 放在 shared 模組，所有需要取用戶 ID 的 controller 都可使用。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * 取得當前登入用戶的 userId
     *
     * @return userId string
     * @throws IllegalStateException 如果用戶未登入
     */
    public static String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new IllegalStateException("用戶未登入");
        }
        return (String) auth.getPrincipal();
    }
}
