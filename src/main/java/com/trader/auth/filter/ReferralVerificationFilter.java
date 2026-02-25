package com.trader.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.referral.service.ReferralService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 推薦碼驗證過濾器
 *
 * 位置：JwtAuthenticationFilter 之後
 * 功能：未通過推薦碼驗證的用戶無法存取交易相關 API
 *
 * 放行條件（任一滿足即放行）：
 * 1. 未認證（anonymous） → 讓 Spring Security 處理 401
 * 2. principal = "monitor" → Monitor API Key
 * 3. role = ADMIN
 * 4. 請求路徑在白名單
 * 5. 用戶已通過推薦碼驗證（查 user_exchange_referral_links 表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferralVerificationFilter extends OncePerRequestFilter {

    private final ReferralService referralService;
    private final ObjectMapper objectMapper;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 白名單路徑 — 未驗證用戶也可存取
     */
    private static final List<String> WHITELISTED_PATHS = List.of(
            "/api/auth/**",
            "/api/referral/**",
            "/api/health",
            "/api/health/deep",
            "/api/user/profile",
            "/api/subscription/**"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // 1. 未認證 → 放行，讓 Spring Security 處理 401
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String principal = auth.getName();

        // 2. Monitor API Key → 放行
        if ("monitor".equals(principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. ADMIN 角色 → 放行
        boolean isAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (isAdmin) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 白名單路徑 → 放行
        String requestPath = request.getRequestURI();
        for (String pattern : WHITELISTED_PATHS) {
            if (PATH_MATCHER.match(pattern, requestPath)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        // 5. 查詢推薦碼驗證狀態
        String userId = principal;
        if (referralService.isVerified(userId)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 6. 未驗證 → 回 403
        log.info("推薦碼未驗證，拒絕存取: userId={} path={}", userId, requestPath);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, String> errorBody = Map.of(
                "error", "REFERRAL_NOT_VERIFIED",
                "message", "請先完成推薦碼驗證"
        );
        objectMapper.writeValue(response.getWriter(), errorBody);
    }
}
