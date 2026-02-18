package com.trader.user.controller;

import com.trader.user.entity.User;
import com.trader.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 取得當前登入用戶資訊
     * GET /api/user/me
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        // TODO: 從 JWT token 取得 userId
        // String userId = SecurityContextHolder.getContext()...
        // return ResponseEntity.ok(userService.findById(userId));
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "尚未實作認證"));
    }

    /**
     * 儲存交易所 API Key
     * PUT /api/user/api-keys
     * Body: { "exchange": "BINANCE", "apiKey": "...", "secretKey": "..." }
     *
     * TODO: 從 SecurityContext 取得 userId + 加密存儲
     */
    @PutMapping("/api-keys")
    public ResponseEntity<?> saveApiKeys(@RequestBody Map<String, String> body) {
        // TODO: 從 JWT token 取得 userId
        // String userId = ...;
        // String exchange = body.getOrDefault("exchange", "BINANCE");
        // userService.saveApiKey(userId, exchange, body.get("apiKey"), body.get("secretKey"));
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "尚未實作認證"));
    }

    /**
     * 查詢用戶已綁定的交易所列表
     * GET /api/user/api-keys
     */
    @GetMapping("/api-keys")
    public ResponseEntity<?> getApiKeys() {
        // TODO: 從 JWT token 取得 userId
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "尚未實作認證"));
    }
}
