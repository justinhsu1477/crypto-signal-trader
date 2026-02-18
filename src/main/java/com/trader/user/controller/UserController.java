package com.trader.user.controller;

import com.trader.shared.util.SecurityUtil;
import com.trader.user.entity.UserApiKey;
import com.trader.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
     * 回傳用戶基本資訊（不含密碼）
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        String userId = SecurityUtil.getCurrentUserId();
        return userService.findById(userId)
                .map(user -> ResponseEntity.ok(Map.of(
                        "userId", user.getUserId(),
                        "email", user.getEmail(),
                        "name", user.getName() != null ? user.getName() : "",
                        "role", user.getRole().name(),
                        "createdAt", user.getCreatedAt().toString()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 儲存交易所 API Key（AES 加密存儲）
     * PUT /api/user/api-keys
     * Body: { "exchange": "BINANCE", "apiKey": "...", "secretKey": "..." }
     */
    @PutMapping("/api-keys")
    public ResponseEntity<?> saveApiKeys(@RequestBody Map<String, String> body) {
        String userId = SecurityUtil.getCurrentUserId();
        String exchange = body.getOrDefault("exchange", "BINANCE");
        String apiKey = body.get("apiKey");
        String secretKey = body.get("secretKey");

        if (apiKey == null || apiKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiKey 和 secretKey 不可為空"));
        }

        UserApiKey saved = userService.saveApiKey(userId, exchange, apiKey, secretKey);
        return ResponseEntity.ok(Map.of(
                "message", "API Key 儲存成功",
                "exchange", saved.getExchange(),
                "updatedAt", saved.getUpdatedAt().toString()
        ));
    }

    /**
     * 查詢用戶已綁定的交易所列表
     * GET /api/user/api-keys
     *
     * 只回傳 metadata，絕不回傳真實 key
     */
    @GetMapping("/api-keys")
    public ResponseEntity<?> getApiKeys() {
        String userId = SecurityUtil.getCurrentUserId();
        List<UserApiKey> keys = userService.getApiKeys(userId);

        List<Map<String, Object>> result = keys.stream()
                .map(k -> Map.<String, Object>of(
                        "exchange", k.getExchange(),
                        "hasApiKey", k.getEncryptedApiKey() != null
                                && !k.getEncryptedApiKey().isBlank(),
                        "updatedAt", k.getUpdatedAt().toString()
                ))
                .toList();

        return ResponseEntity.ok(result);
    }
}
