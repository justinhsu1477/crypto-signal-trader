package com.trader.auth.controller;

import com.trader.auth.dto.*;
import com.trader.auth.exception.EmailNotVerifiedException;
import com.trader.auth.service.AuthService;
import com.trader.auth.service.EmailVerificationService;
import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.service.AuditService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final UserRepository userRepository;

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
                    .message("註冊成功，請查收驗證信")
                    .needsVerification(true)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        } catch (IllegalStateException e) {
            // Rate limit（發送過於頻繁）
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
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
        } catch (EmailNotVerifiedException e) {
            // Email 未驗證 → 403，前端導向驗證頁
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "EMAIL_NOT_VERIFIED", "email", request.getEmail()));
        } catch (IllegalArgumentException e) {
            // 登入失敗：記錄審計日誌（用於防暴力破解）
            auditService.logFailedAuth(request.getEmail(), clientIp, e.getMessage());

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * 驗證 Email OTP
     * POST /api/auth/verify-email
     * Body: {@link VerifyEmailRequest}
     */
    @PostMapping("/verify-email")
    @Transactional
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        try {
            emailVerificationService.verifyCode(request.getEmail(), request.getCode());

            // 驗證通過 → emailVerified = true
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
     * Body: {@link ResendCodeRequest}
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
