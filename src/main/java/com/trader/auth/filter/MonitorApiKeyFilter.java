package com.trader.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Monitor API Key 認證過濾器
 *
 * 從 X-Api-Key header 驗證 Python Monitor 的身份。
 * 驗證通過後設定 SecurityContext，principal = "monitor"。
 *
 * 優先級：在 JwtAuthenticationFilter 之前執行。
 * 如果 SecurityContext 已經有認證（JWT 先通過了），則跳過。
 */
@Slf4j
@Component
public class MonitorApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String MONITOR_PRINCIPAL = "monitor";

    @Value("${monitor.api-key:}")
    private String monitorApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        // 如果已經有認證（JWT 通過了），跳過
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !monitorApiKey.isBlank() && apiKey.equals(monitorApiKey)) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            MONITOR_PRINCIPAL,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_MONITOR"))
                    );
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Monitor API Key 認證成功: {}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }
}
