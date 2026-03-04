package com.trader.auth.controller;

import com.trader.auth.config.LineLoginConfig;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.service.JwtService;
import com.trader.auth.service.OAuthService;
import com.trader.auth.util.CookieUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * OAuth 第三方登入 Controller
 *
 * 流程：
 * 1. GET  /api/auth/oauth/line           → 302 redirect to LINE 授權頁
 * 2. GET  /api/auth/oauth/line/callback   → 處理 callback → 302 redirect to frontend with ticket
 * 3. POST /api/auth/oauth/complete        → 驗 ticket → 設 HttpOnly Cookie → 回傳用戶資訊
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;
    private final LineLoginConfig lineLoginConfig;
    private final JwtService jwtService;

    /**
     * Step 1: 導向 LINE 授權頁
     */
    @GetMapping("/line")
    public void redirectToLine(HttpServletResponse response) throws IOException {
        if (!lineLoginConfig.isEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "LINE Login 未啟用");
            return;
        }

        String authUrl = oauthService.generateLineAuthUrl();
        response.sendRedirect(authUrl);
    }

    /**
     * Step 2: LINE callback → 驗證 + 帳號解析 → redirect to frontend with ticket
     */
    @GetMapping("/line/callback")
    public void handleLineCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletResponse response) throws IOException {

        try {
            String ticket = oauthService.handleLineCallback(code, state);

            // Redirect to frontend login page with ticket
            String frontendUrl = deriveFrontendBaseUrl();
            response.sendRedirect(frontendUrl + "/login?oauth=pending&ticket=" + ticket);

        } catch (Exception e) {
            log.error("LINE OAuth callback 失敗: {}", e.getMessage(), e);
            String frontendUrl = deriveFrontendBaseUrl();
            // 不暴露內部錯誤訊息到 URL，僅用通用錯誤碼
            response.sendRedirect(frontendUrl + "/login?oauth=error");
        }
    }

    /**
     * Step 3: 前端用 ticket 交換 HttpOnly Cookie
     */
    @PostMapping("/complete")
    public ResponseEntity<?> completeOAuth(
            @RequestBody Map<String, String> body,
            HttpServletResponse httpResponse) {

        String ticket = body.get("ticket");
        if (ticket == null || ticket.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 ticket"));
        }

        try {
            LoginResponse loginResponse = oauthService.completeLogin(ticket);

            // 設定 HttpOnly Cookie（與 AuthController.login 相同模式）
            CookieUtil.addAccessTokenCookie(httpResponse, loginResponse.getToken(),
                    jwtService.getExpirationMs() / 1000,
                    jwtService.isCookieSecure());
            CookieUtil.addRefreshTokenCookie(httpResponse, loginResponse.getRefreshToken(),
                    jwtService.getRefreshExpirationMs() / 1000,
                    jwtService.isCookieSecure());

            return ResponseEntity.ok(Map.of(
                    "userId", loginResponse.getUserId(),
                    "email", loginResponse.getEmail() != null ? loginResponse.getEmail() : "",
                    "role", loginResponse.getRole(),
                    "expiresIn", jwtService.getExpirationMs() / 1000
            ));
        } catch (Exception e) {
            log.warn("OAuth complete 失敗: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 從 callback URL 推導前端 base URL
     * 例如 https://hook-fi.com/api/auth/oauth/line/callback → https://hook-fi.com
     */
    private String deriveFrontendBaseUrl() {
        String callbackUrl = lineLoginConfig.getCallbackUrl();
        int apiIndex = callbackUrl.indexOf("/api/");
        if (apiIndex > 0) {
            return callbackUrl.substring(0, apiIndex);
        }
        // fallback
        return "https://hook-fi.com";
    }
}
