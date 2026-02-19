package com.trader.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.service.AuditService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定義認證進入點
 *
 * Spring Security 層級的認證失敗處理器（401 Unauthorized）。
 * 當請求缺少或有無效的 JWT token 時，此 handler 會被觸發。
 *
 * 作用：
 * 1. 攔截 Spring Security 的 401 回應
 * 2. 回傳統一的 JSON 格式而非 HTML 頁面
 * 3. 記錄認證失敗事件用於審計
 *
 * 觸發時機：
 * - GET /api/dashboard/overview （無 Authorization header）
 * - GET /api/dashboard/overview ?token=invalid （無效 token）
 * - GET /api/user/profile （token 已過期）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Override
    public void commence(HttpServletRequest request,
                        HttpServletResponse response,
                        AuthenticationException authException)
            throws IOException, ServletException {

        String clientIp = getClientIp(request);
        String requestUri = request.getRequestURI();
        String authHeader = request.getHeader("Authorization");

        // 記錄認證失敗事件（用於審計）
        log.warn("認證失敗 [{}] IP={} Header={} Message={}",
                requestUri, clientIp,
                authHeader != null ? "present" : "missing",
                authException.getMessage());

        // 非同步記錄審計日誌
        auditService.log(
                null,
                "API_ACCESS_FAILED",
                requestUri,
                "FAILED",
                clientIp,
                authHeader != null ? "Invalid token" : "Missing Authorization header"
        );

        // 設置回應格式為 JSON
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // 構建統一的錯誤回應
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("未授權 (401)")
                .message("請提供有效的 Bearer Token。格式: Authorization: Bearer {token}")
                .build();

        // 寫入 JSON 回應
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    /**
     * 從 request 中提取真實的客戶端 IP
     * 支援：直連 IP、X-Forwarded-For、X-Real-IP
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }

        return request.getRemoteAddr();
    }
}
