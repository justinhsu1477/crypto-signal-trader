package com.trader.auth.controller;

import com.trader.auth.dto.*;
import com.trader.auth.exception.EmailNotVerifiedException;
import com.trader.auth.service.AuthService;
import com.trader.auth.service.EmailVerificationService;
import com.trader.auth.service.JwtService;
import com.trader.auth.service.PasswordResetService;
import com.trader.auth.util.CookieUtil;
import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.service.AuditService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    /**
     * 用戶註冊
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = authService.register(request);
            return ResponseEntity.ok(RegisterResponse.builder()
                    .userId(user.getUserId())
                    .email(user.getEmail())
                    .message("註冊成功，請查收驗證信")
                    .needsVerification(true)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 用戶登入
     * POST /api/auth/login
     *
     * Token 改為 HttpOnly Cookie 回傳，Response Body 不再包含 token/refreshToken。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);

        try {
            LoginResponse response = authService.login(request);

            // 設定 HttpOnly Cookie
            CookieUtil.addAccessTokenCookie(httpResponse, response.getToken(),
                    jwtService.getExpirationMs() / 1000);
            CookieUtil.addRefreshTokenCookie(httpResponse, response.getRefreshToken(),
                    jwtService.getRefreshExpirationMs() / 1000);

            auditService.log(
                    response.getUserId(),
                    "LOGIN",
                    "/api/auth/login",
                    "SUCCESS",
                    clientIp,
                    "Email: " + request.getEmail()
            );

            // Response Body 不再回傳 token — 改用 Cookie
            return ResponseEntity.ok(Map.of(
                    "userId", response.getUserId(),
                    "email", response.getEmail(),
                    "role", response.getRole(),
                    "expiresIn", jwtService.getExpirationMs() / 1000
            ));
        } catch (EmailNotVerifiedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "EMAIL_NOT_VERIFIED", "email", request.getEmail()));
        } catch (IllegalArgumentException e) {
            auditService.logFailedAuth(request.getEmail(), clientIp, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 驗證 Email OTP
     * POST /api/auth/verify-email
     */
    @PostMapping("/verify-email")
    @Transactional
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        try {
            emailVerificationService.verifyCode(request.getEmail(), request.getCode());

            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("找不到此 Email 的帳號"));
            user.setEmailVerified(true);
            userRepository.save(user);

            log.info("Email 驗證成功: email={}", request.getEmail());
            return ResponseEntity.ok(Map.of("message", "Email 驗證成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 重新發送 OTP 驗證碼
     * POST /api/auth/resend-code
     */
    @PostMapping("/resend-code")
    public ResponseEntity<?> resendCode(@Valid @RequestBody ResendCodeRequest request) {
        try {
            emailVerificationService.resendCode(request.getEmail());
            return ResponseEntity.ok(Map.of("message", "驗證碼已重新發送"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     *
     * 改為從 HttpOnly Cookie 讀取 Refresh Token（不再從 Request Body）。
     * 成功後設定新的 Cookie pair。
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);

        // 從 Cookie 讀取 Refresh Token
        String refreshToken = CookieUtil.extractRefreshToken(httpRequest);
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error("缺少 Refresh Token").build());
        }

        try {
            LoginResponse response = authService.refreshToken(refreshToken);

            // 設定新的 HttpOnly Cookie
            CookieUtil.addAccessTokenCookie(httpResponse, response.getToken(),
                    jwtService.getExpirationMs() / 1000);
            CookieUtil.addRefreshTokenCookie(httpResponse, response.getRefreshToken(),
                    jwtService.getRefreshExpirationMs() / 1000);

            auditService.log(
                    response.getUserId(),
                    "REFRESH_TOKEN",
                    "/api/auth/refresh",
                    "SUCCESS",
                    clientIp,
                    ""
            );

            return ResponseEntity.ok(Map.of(
                    "userId", response.getUserId(),
                    "email", response.getEmail(),
                    "role", response.getRole(),
                    "expiresIn", jwtService.getExpirationMs() / 1000
            ));
        } catch (IllegalArgumentException e) {
            auditService.log(null, "REFRESH_TOKEN", "/api/auth/refresh",
                    "FAILED", clientIp, e.getMessage());

            // 清除無效的 Cookie
            CookieUtil.clearAuthCookies(httpResponse);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 登出
     * POST /api/auth/logout
     *
     * 清除 HttpOnly Cookie，結束 session。
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        CookieUtil.clearAuthCookies(httpResponse);

        String clientIp = getClientIp(httpRequest);
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String userId = (auth != null && auth.getPrincipal() instanceof String)
                ? (String) auth.getPrincipal() : null;

        if (userId != null) {
            auditService.log(userId, "LOGOUT", "/api/auth/logout", "SUCCESS", clientIp, "");
        }

        return ResponseEntity.ok(Map.of("message", "登出成功"));
    }

    // ========== 密碼管理 ==========

    /**
     * 修改密碼（已登入用戶）
     * POST /api/auth/change-password
     *
     * 成功後清除 Cookie → 前端收到後跳轉登入頁。
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            Authentication authentication,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);
        String userId = (String) authentication.getPrincipal();

        try {
            passwordResetService.changePassword(userId, request);

            // 清除 Cookie → 強制重新登入
            CookieUtil.clearAuthCookies(httpResponse);

            auditService.log(userId, "CHANGE_PASSWORD", "/api/auth/change-password",
                    "SUCCESS", clientIp, "");

            return ResponseEntity.ok(Map.of("message", "密碼修改成功，請重新登入"));
        } catch (IllegalArgumentException e) {
            auditService.log(userId, "CHANGE_PASSWORD", "/api/auth/change-password",
                    "FAILED", clientIp, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 忘記密碼（公開端點）
     * POST /api/auth/forgot-password
     *
     * 安全原則：無論 email 是否存在，永遠回相同訊息（防枚舉）。
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.requestPasswordReset(request.getEmail());
        } catch (Exception e) {
            log.error("忘記密碼處理異常（靜默）: email={}", request.getEmail(), e);
        }

        // 永遠回相同訊息（防枚舉）
        return ResponseEntity.ok(Map.of(
                "message", "若此 Email 已註冊，我們已發送密碼重設連結"));
    }

    /**
     * 密碼重設（公開端點，需要有效 token）
     * POST /api/auth/reset-password
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request);
            return ResponseEntity.ok(Map.of("message", "密碼重設成功，請重新登入"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 取得當前登入用戶資訊
     * GET /api/auth/me
     *
     * 前端改用 HttpOnly Cookie 後無法讀取 JWT，
     * 需要此端點在頁面載入時確認登入狀態。
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error("未登入").build());
        }

        String userId = (String) authentication.getPrincipal();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error("用戶不存在").build());
        }

        return ResponseEntity.ok(Map.of(
                "userId", user.getUserId(),
                "email", user.getEmail(),
                "role", user.getRole().name()
        ));
    }

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
