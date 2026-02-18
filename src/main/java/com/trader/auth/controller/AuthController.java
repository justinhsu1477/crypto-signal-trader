package com.trader.auth.controller;

import com.trader.auth.dto.LoginRequest;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.dto.RegisterRequest;
import com.trader.auth.service.AuthService;
import com.trader.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * TODO: 實作完整登入邏輯
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // TODO: 呼叫 authService.login(request)
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "login 尚未實作"));
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     * Body: { "refreshToken": "..." }
     *
     * TODO: 實作 token 刷新
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> body) {
        // TODO: 呼叫 authService.refreshToken(body.get("refreshToken"))
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "refreshToken 尚未實作"));
    }
}
