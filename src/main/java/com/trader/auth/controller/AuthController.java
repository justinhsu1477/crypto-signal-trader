package com.trader.auth.controller;

import com.trader.auth.dto.*;
import com.trader.auth.service.AuthService;
import com.trader.shared.dto.ErrorResponse;
import com.trader.user.entity.User;
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
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
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
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            LoginResponse response = authService.refreshToken(request.getRefreshToken());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder().error(e.getMessage()).build());
        }
    }
}
