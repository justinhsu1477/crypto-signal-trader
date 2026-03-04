package com.trader.auth.service;

import com.trader.auth.dto.LoginRequest;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.dto.RegisterRequest;
import com.trader.auth.exception.EmailNotVerifiedException;
import com.trader.auth.util.EmailNormalizer;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trader.shared.config.AppConstants;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 認證服務
 *
 * 負責用戶註冊、登入、Token 刷新。
 * 含 per-account 登入失敗鎖定（5 次失敗後鎖定 15 分鐘）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;

    // ===== Per-Account 登入失敗鎖定 =====
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000; // 15 分鐘

    /** key = normalized email → 失敗記錄 */
    private final ConcurrentHashMap<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    static class LoginAttempt {
        final AtomicInteger failCount = new AtomicInteger(0);
        volatile long lockedUntil = 0;
        volatile long firstFailTime = 0;
    }

    /**
     * 用戶註冊
     *
     * @param request 註冊請求 (email, password, name)
     * @return 新建立的 User
     */
    @Transactional
    public User register(RegisterRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.getEmail());

        if (!request.isTermsAccepted()) {
            throw new IllegalArgumentException("必須同意服務條款與風險聲明");
        }

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("Email 已被註冊: " + normalizedEmail);
        }

        User user = User.builder()
                .userId(UUID.randomUUID().toString())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .emailVerified(false)
                .autoTradeEnabled(false)
                .build();

        userRepository.save(user);
        log.info("用戶註冊成功（待驗證）: email={}", user.getEmail());

        // 發送 OTP 驗證碼
        emailVerificationService.generateAndSend(normalizedEmail);

        return user;
    }

    /**
     * 用戶登入
     *
     * 安全機制：
     * - 錯誤訊息統一「帳號或密碼錯誤」，防止 email 列舉攻擊
     * - 連續 5 次密碼錯誤 → 帳號鎖定 15 分鐘（per-account，不受 IP 切換影響）
     *
     * @param request 登入請求 (email, password)
     * @return LoginResponse (含 JWT + refresh token)
     */
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.getEmail());

        // 檢查帳號是否被鎖定
        checkAccountLockout(normalizedEmail);

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> {
                    recordLoginFailure(normalizedEmail);
                    return new IllegalArgumentException("帳號或密碼錯誤");
                });

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("帳號已停用");
        }

        // OAuth-only 帳號無密碼 → 引導用 LINE 登入
        if (!user.hasPassword()) {
            throw new IllegalArgumentException("此帳號使用第三方登入，請用 LINE 登入");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginFailure(normalizedEmail);
            throw new IllegalArgumentException("帳號或密碼錯誤");
        }

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("EMAIL_NOT_VERIFIED");
        }

        // 登入成功 → 清除失敗記錄
        clearLoginFailure(normalizedEmail);

        String role = user.getRole().name();
        String token = jwtService.generateToken(user.getUserId(), role);
        String refreshToken = jwtService.generateRefreshToken(user.getUserId(), role);

        log.info("用戶登入成功: email={} role={}", user.getEmail(), role);

        return LoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .expiresIn(jwtService.getExpirationMs() / 1000)
                .userId(user.getUserId())
                .email(user.getEmail())
                .role(role)
                .build();
    }

    /**
     * OAuth 登入（無密碼驗證，由 OAuthService 呼叫）
     *
     * @param user 已驗證的 OAuth 用戶
     * @return LoginResponse (含 JWT + refresh token)
     */
    public LoginResponse loginByOAuth(User user) {
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("帳號已停用");
        }

        String role = user.getRole().name();
        String token = jwtService.generateToken(user.getUserId(), role);
        String refreshToken = jwtService.generateRefreshToken(user.getUserId(), role);

        log.info("OAuth 登入成功: userId={} role={}", user.getUserId(), role);

        return LoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .expiresIn(jwtService.getExpirationMs() / 1000)
                .userId(user.getUserId())
                .email(user.getEmail())
                .role(role)
                .build();
    }

    // ===== Login Lockout Helpers =====

    private void checkAccountLockout(String email) {
        LoginAttempt attempt = loginAttempts.get(email);
        if (attempt == null) return;

        long now = System.currentTimeMillis();
        if (attempt.lockedUntil > now) {
            long remainSec = (attempt.lockedUntil - now) / 1000;
            log.warn("帳號鎖定中: email={}, 剩餘 {}s", email, remainSec);
            throw new IllegalStateException(
                    String.format("帳號已被暫時鎖定，請 %d 分鐘後再試", (remainSec / 60) + 1));
        }

        // 鎖定已過期 → 自動解鎖
        if (attempt.lockedUntil > 0 && attempt.lockedUntil <= now) {
            loginAttempts.remove(email);
        }
    }

    private void recordLoginFailure(String email) {
        LoginAttempt attempt = loginAttempts.computeIfAbsent(email, k -> new LoginAttempt());
        long now = System.currentTimeMillis();

        // 如果距離首次失敗超過 lockout 時長，重新計數
        if (attempt.firstFailTime > 0 && now - attempt.firstFailTime > LOCKOUT_DURATION_MS) {
            attempt.failCount.set(0);
            attempt.lockedUntil = 0;
        }

        if (attempt.failCount.get() == 0) {
            attempt.firstFailTime = now;
        }

        int fails = attempt.failCount.incrementAndGet();
        log.warn("登入失敗: email={}, 累計={}/{}", email, fails, MAX_LOGIN_ATTEMPTS);

        if (fails >= MAX_LOGIN_ATTEMPTS) {
            attempt.lockedUntil = now + LOCKOUT_DURATION_MS;
            log.warn("帳號已鎖定 15 分鐘: email={}", email);
        }
    }

    void clearLoginFailure(String email) {
        loginAttempts.remove(email);
    }

    /** 供測試用 — 取得目前失敗次數 */
    int getFailedAttempts(String email) {
        LoginAttempt attempt = loginAttempts.get(email);
        return attempt != null ? attempt.failCount.get() : 0;
    }

    /** 供測試用 — 帳號是否被鎖定 */
    boolean isAccountLocked(String email) {
        LoginAttempt attempt = loginAttempts.get(email);
        return attempt != null && attempt.lockedUntil > System.currentTimeMillis();
    }

    /**
     * 刷新 Token（Token Rotation — 每次刷新發新的 refresh token）
     *
     * @param refreshToken 舊的 refresh token
     * @return 新的 LoginResponse（含新 JWT + 新 refresh token）
     */
    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh Token 無效或已過期");
        }

        // 驗證 type claim — 防止 access token 冒充 refresh token
        String tokenType = jwtService.extractType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            log.warn("Token type 不符: expected=refresh, actual={}", tokenType);
            throw new IllegalArgumentException("Refresh Token 無效或已過期");
        }

        String userId = jwtService.extractUserId(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("帳號已停用");
        }

        // 密碼變更後舊 refresh token 立即失效（iat < passwordChangedAt）
        if (user.getPasswordChangedAt() != null) {
            Date iat = jwtService.extractIssuedAt(refreshToken);
            if (iat != null) {
                var tokenIssuedAt = iat.toInstant()
                        .atZone(AppConstants.ZONE_ID)
                        .toLocalDateTime();
                if (tokenIssuedAt.isBefore(user.getPasswordChangedAt())) {
                    log.warn("Refresh Token 已因密碼變更而失效: userId={}", userId);
                    throw new IllegalArgumentException("Refresh Token 無效或已過期");
                }
            }
        }

        String role = user.getRole().name();
        String newToken = jwtService.generateToken(userId, role);
        String newRefreshToken = jwtService.generateRefreshToken(userId, role);

        log.info("Token 刷新成功: userId={} role={}", userId, role);

        return LoginResponse.builder()
                .token(newToken)
                .refreshToken(newRefreshToken)
                .expiresIn(jwtService.getExpirationMs() / 1000)
                .userId(user.getUserId())
                .email(user.getEmail())
                .role(role)
                .build();
    }
}
