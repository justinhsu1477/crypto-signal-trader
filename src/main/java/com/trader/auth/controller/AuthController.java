package com.trader.auth.controller;

import com.trader.auth.dto.*;
import com.trader.auth.service.AuthService;
import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.service.AuditService;
import com.trader.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    /**
     * 用戶註冊
     * POST /api/auth/register
     * Body: {@link RegisterRequest}
     *
     * @return {@link RegisterResponse}
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = authService.register(request);
            return ResponseEntity.ok(RegisterResponse.builder()
                    .userId(user.getUserId())
                    .email(user.getEmail())
                    .message("註冊成功")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 用戶登入
     * POST /api/auth/login
     * Body: {@link LoginRequest}
     *
     * @return {@link LoginResponse}
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);

        try {
            LoginResponse response = authService.login(request);

            // 登入成功：記錄審計日誌
            auditService.log(
                    response.getUserId(),
                    "LOGIN",
                    "/api/auth/login",
                    "SUCCESS",
                    clientIp,
                    "Email: " + request.getEmail()
            );

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // 登入失敗：記錄審計日誌（用於防暴力破解）
            auditService.logFailedAuth(request.getEmail(), clientIp, e.getMessage());

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     * Body: {@link RefreshTokenRequest}
     *
     * @return {@link LoginResponse}（新的 token pair）
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request,
                                          HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);

        try {
            LoginResponse response = authService.refreshToken(request.getRefreshToken());

            // Token 刷新成功
            auditService.log(
                    response.getUserId(),
                    "REFRESH_TOKEN",
                    "/api/auth/refresh",
                    "SUCCESS",
                    clientIp,
                    ""
            );

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Token 刷新失敗（通常是 refresh token 過期或無效）
            auditService.log(
                    null,
                    "REFRESH_TOKEN",
                    "/api/auth/refresh",
                    "FAILED",
                    clientIp,
                    e.getMessage()
            );

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 從客戶端 IP（支援代理）
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
