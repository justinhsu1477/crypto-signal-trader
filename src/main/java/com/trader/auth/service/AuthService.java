package com.trader.auth.service;

import com.trader.auth.dto.LoginRequest;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.dto.RegisterRequest;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 認證服務
 *
 * 負責用戶註冊、登入、Token 刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 用戶註冊
     *
     * @param request 註冊請求 (email, password, name)
     * @return 新建立的 User
     */
    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email 已被註冊: " + request.getEmail());
        }

        User user = User.builder()
                .userId(UUID.randomUUID().toString())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .build();

        log.info("用戶註冊成功: email={}", user.getEmail());
        return userRepository.save(user);
    }

    /**
     * 用戶登入
     *
     * 錯誤訊息統一「帳號或密碼錯誤」，防止 email 列舉攻擊。
     *
     * @param request 登入請求 (email, password)
     * @return LoginResponse (含 JWT + refresh token)
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("帳號或密碼錯誤"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("帳號已停用");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("帳號或密碼錯誤");
        }

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
     * 刷新 Token（Token Rotation — 每次刷新發新的 refresh token）
     *
     * @param refreshToken 舊的 refresh token
     * @return 新的 LoginResponse（含新 JWT + 新 refresh token）
     */
    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh Token 無效或已過期");
        }

        String userId = jwtService.extractUserId(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("帳號已停用");
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
