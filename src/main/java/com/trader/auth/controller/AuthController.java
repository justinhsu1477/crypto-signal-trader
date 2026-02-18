package com.trader.auth.controller;

import com.trader.auth.dto.LoginRequest;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.dto.RegisterRequest;
import com.trader.auth.service.AuthService;
import com.trader.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用戶註冊
     * POST /api/auth/register
     * Body: { "email": "...", "password": "...", "name": "..." }
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = authService.register(request);
            return ResponseEntity.ok(Map.of(
                    "userId", user.getUserId(),
                    "email", user.getEmail(),
                    "message", "註冊成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 用戶登入
     * POST /api/auth/login
     * Body: { "email": "...", "password": "..." }
     *
     * 回傳: { token, refreshToken, expiresIn, userId, email }
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     * Body: { "refreshToken": "..." }
     *
     * 回傳: 新的 { token, refreshToken, expiresIn, userId, email }
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "refreshToken 不可為空"));
        }
        try {
            LoginResponse response = authService.refreshToken(refreshToken);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
