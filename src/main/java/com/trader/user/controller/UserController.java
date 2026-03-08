package com.trader.user.controller;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.SecurityUtil;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.dto.ApiKeyMetadata;
import com.trader.user.dto.SaveApiKeyRequest;
import com.trader.user.dto.SaveApiKeyResponse;
import com.trader.user.dto.UserProfileResponse;
import com.trader.user.entity.UserApiKey;
import com.trader.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TradeRepository tradeRepository;

    /**
     * 取得當前登入用戶資訊
     * GET /api/user/me
     *
     * @return {@link UserProfileResponse}（不含密碼）
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        String userId = SecurityUtil.getCurrentUserId();
        return userService.findById(userId)
                .map(user -> ResponseEntity.ok(UserProfileResponse.builder()
                        .userId(user.getUserId())
                        .email(user.getEmail())
                        .name(user.getName() != null ? user.getName() : "")
                        .role(user.getRole().name())
                        .createdAt(user.getCreatedAt().toString())
                        .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 儲存交易所 API Key（AES 加密存儲）
     * PUT /api/user/api-keys
     * Body: {@link SaveApiKeyRequest}
     *
     * 交易所切換驗證：一用戶一交易所，切換前必須先平倉所有持倉。
     *
     * @return {@link SaveApiKeyResponse}
     */
    @PutMapping("/api-keys")
    public ResponseEntity<?> saveApiKeys(@Valid @RequestBody SaveApiKeyRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        String newExchange = request.getExchange();

        // 交易所切換檢查：如果用戶已綁定不同交易所的 API Key，檢查是否有 OPEN trade
        List<UserApiKey> existingKeys = userService.getApiKeys(userId);
        if (!existingKeys.isEmpty()) {
            UserApiKey existing = existingKeys.get(0);
            if (!existing.getExchange().equals(newExchange)) {
                // 切換交易所 — 檢查是否有未平倉交易
                long openTradeCount = tradeRepository.countByUserIdAndStatus(userId, "OPEN");
                if (openTradeCount > 0) {
                    log.warn("用戶 {} 嘗試切換交易所 {} → {}，但有 {} 筆未平倉交易",
                            userId, existing.getExchange(), newExchange, openTradeCount);
                    return ResponseEntity.badRequest()
                            .body(ErrorResponse.builder()
                                    .error("EXCHANGE_SWITCH_BLOCKED")
                                    .message("切換交易所前請先平倉所有持倉（目前有 " + openTradeCount + " 筆未平倉交易）")
                                    .build());
                }
                log.info("用戶 {} 切換交易所 {} → {}（無未平倉交易，允許切換）",
                        userId, existing.getExchange(), newExchange);
            }
        }

        UserApiKey saved = userService.saveApiKey(
                userId, request.getExchange(),
                request.getApiKey(), request.getSecretKey());

        return ResponseEntity.ok(SaveApiKeyResponse.builder()
                .message("API Key 儲存成功")
                .exchange(saved.getExchange())
                .updatedAt(saved.getUpdatedAt().toString())
                .build());
    }

    /**
     * 查詢用戶已綁定的交易所列表
     * GET /api/user/api-keys
     *
     * @return {@link List}<{@link ApiKeyMetadata}>（只含 metadata，絕不回傳真實 key）
     */
    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyMetadata>> getApiKeys() {
        String userId = SecurityUtil.getCurrentUserId();
        List<UserApiKey> keys = userService.getApiKeys(userId);

        List<ApiKeyMetadata> result = keys.stream()
                .map(k -> ApiKeyMetadata.builder()
                        .exchange(k.getExchange())
                        .hasApiKey(k.getEncryptedApiKey() != null
                                && !k.getEncryptedApiKey().isBlank())
                        .updatedAt(k.getUpdatedAt().toString())
                        .build())
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * GDPR 帳號刪除
     * DELETE /api/user/account
     *
     * 軟刪除：停用帳號 + 匿名化 PII + 清除敏感資料（API Key、Webhook 等）
     * 前端應在成功後登出。
     */
    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount() {
        String userId = SecurityUtil.getCurrentUserId();
        try {
            userService.deleteAccount(userId);
            log.info("帳號刪除成功: userId={}", userId);
            return ResponseEntity.ok(java.util.Map.of("message", "帳號已刪除"));
        } catch (IllegalArgumentException e) {
            log.warn("帳號刪除失敗: userId={}, reason={}", userId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .error("DELETE_FAILED")
                            .message(e.getMessage())
                            .build());
        }
    }
}
