package com.trader.shared.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全工具類
 *
 * 從 SecurityContext 取得當前登入用戶的 ID 和角色。
 * 放在 shared 模組，所有需要取用戶資訊的 controller 都可使用。
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

    /**
     * 取得當前登入用戶的角色（USER / ADMIN）
     *
     * @return role string（去掉 ROLE_ 前綴），未登入時回傳 null
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("USER");
    }
}
