package com.trader.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定義存取被拒處理器
 *
 * Spring Security 層級的授權失敗處理器（403 Forbidden）。
 * 當已認證的用戶試圖訪問沒有權限的資源時，此 handler 會被觸發。
 *
 * 目前用途：
 * - 為未來的 RBAC（角色權限）做準備
 * - 現階段所有認證用戶都有相同權限，此 handler 幾乎不會被觸發
 *
 * 觸發時機（未來）：
 * - 普通用戶訪問 /api/admin/users （需要 ROLE_ADMIN）
 * - 用戶訪問他人資料 （需要 @PreAuthorize）
 *
 * 當前作用：
 * 1. 回傳統一的 JSON 格式而非 HTML 頁面
 * 2. 記錄權限拒絕事件用於審計
 * 3. 為未來擴展預留接口
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                      HttpServletResponse response,
                      AccessDeniedException accessDeniedException)
            throws IOException, ServletException {

        String currentUserId = "unknown";
        try {
            currentUserId = SecurityUtil.getCurrentUserId();
        } catch (IllegalStateException e) {
            // 用戶未認證（不應該走到這裡，因為未認證會先被 AuthenticationEntryPoint 攔截）
        }

        String clientIp = getClientIp(request);
        String requestUri = request.getRequestURI();

        // 記錄權限被拒事件（用於審計）
        log.warn("權限被拒 [{}] UserId={} IP={} Message={}",
                requestUri, currentUserId, clientIp,
                accessDeniedException.getMessage());

        // 設置回應格式為 JSON
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);

        // 構建統一的錯誤回應
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("禁止存取 (403)")
                .message("您沒有權限執行此操作")
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
