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
 *
 * TODO: 實作完整邏輯
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
        // TODO: 檢查 email 是否已存在
        // TODO: hash password
        // TODO: 建立 User entity 並存入 DB
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email 已被註冊: " + request.getEmail());
        }

        User user = User.builder()
                .userId(UUID.randomUUID().toString())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .build();

        return userRepository.save(user);
    }

    /**
     * 用戶登入
     *
     * @param request 登入請求 (email, password)
     * @return LoginResponse (含 JWT token)
     */
    public LoginResponse login(LoginRequest request) {
        // TODO: 查詢用戶 → 驗證密碼 → 生成 JWT
        throw new UnsupportedOperationException("login 尚未實作");
    }

    /**
     * 刷新 Token
     *
     * @param refreshToken 舊的 refresh token
     * @return 新的 LoginResponse
     */
    public LoginResponse refreshToken(String refreshToken) {
        // TODO: 驗證 refreshToken → 生成新 JWT
        throw new UnsupportedOperationException("refreshToken 尚未實作");
    }
}
