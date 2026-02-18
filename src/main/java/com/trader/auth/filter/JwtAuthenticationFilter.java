package com.trader.auth.filter;

import com.trader.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 認證過濾器
 *
 * 從 Authorization: Bearer {token} 標頭中提取 JWT，
 * 驗證後設定 Spring Security Context。
 *
 * TODO: 實作完整的 token 解析和 SecurityContext 設定
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

        // TODO: 實作 JWT 認證邏輯
        // 1. 從 Authorization header 取得 Bearer token
        // 2. 呼叫 jwtService.validateToken(token)
        // 3. 呼叫 jwtService.extractUserId(token)
        // 4. 建立 Authentication 物件放入 SecurityContextHolder
        //
        // String authHeader = request.getHeader("Authorization");
        // if (authHeader != null && authHeader.startsWith("Bearer ")) {
        //     String token = authHeader.substring(7);
        //     if (jwtService.validateToken(token)) {
        //         String userId = jwtService.extractUserId(token);
        //         // set SecurityContext...
        //     }
        // }

        filterChain.doFilter(request, response);
    }
}
