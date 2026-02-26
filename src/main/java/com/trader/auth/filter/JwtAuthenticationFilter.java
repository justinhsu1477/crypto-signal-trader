package com.trader.auth.filter;

import com.trader.auth.service.JwtService;
import com.trader.auth.util.CookieUtil;
import com.trader.trading.service.TradeRecordService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 認證過濾器
 *
 * 優先從 Authorization: Bearer {token} 標頭提取 JWT（向後相容 Monitor API），
 * 若無 header，則 fallback 從 HttpOnly Cookie 提取（前端 Dashboard 使用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String token = null;
        String authSource = null;

        // 1. 優先從 Authorization header 讀取（Monitor API / 向後相容）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
            authSource = "header";
        }

        // 2. Fallback: 從 HttpOnly Cookie 讀取（前端 Dashboard）
        if (token == null) {
            token = CookieUtil.extractAccessToken(request);
            if (token != null) {
                authSource = "cookie";
            }
        }

        // 3. 驗證 Token
        if (token != null) {
            try {
                if (jwtService.validateToken(token)) {
                    String userId = jwtService.extractUserId(token);
                    String role = jwtService.extractRole(token);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                            );
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    TradeRecordService.setCurrentUserId(userId);
                    log.debug("JWT 認證成功 ({}): userId={} role={}", authSource, userId, role);
                }
            } catch (Exception e) {
                log.warn("JWT 處理失敗 ({}): {}", authSource, e.getMessage());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TradeRecordService.clearCurrentUserId();
        }
    }
}
