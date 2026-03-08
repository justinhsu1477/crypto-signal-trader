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
import java.util.Set;

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

    /** 交易所切換時，這些狀態視為「未收斂」，必須全部結清才能切換 */
    private static final List<String> UNSETTLED_STATUSES = List.of("OPEN", "PENDING_CLOSE");

    /** 後端支援的交易所白名單（大寫） */
    private static final Set<String> SUPPORTED_EXCHANGES = Set.of("BINANCE", "BYBIT", "BITGET");

    /**
     * 儲存交易所 API Key（AES 加密存儲）
     * PUT /api/user/api-keys
     * Body: {@link SaveApiKeyRequest}
     *
     * 驗證：
     * 1. exchange 白名單（只接受 SUPPORTED_EXCHANGES）
     * 2. 交易所切換時，OPEN + PENDING_CLOSE 都視為未收斂，必須全部結清才能切換
     *
     * @return {@link SaveApiKeyResponse}
     */
    @PutMapping("/api-keys")
    public ResponseEntity<?> saveApiKeys(@Valid @RequestBody SaveApiKeyRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        String newExchange = request.getExchange().trim().toUpperCase();

        // 1. 交易所白名單驗證
        if (!SUPPORTED_EXCHANGES.contains(newExchange)) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .error("UNSUPPORTED_EXCHANGE")
                            .message("不支援的交易所: " + request.getExchange()
                                    + "，目前支援: " + SUPPORTED_EXCHANGES)
                            .build());
        }

        // 2. Bitget passphrase 驗證
        if ("BITGET".equals(newExchange)
                && (request.getPassphrase() == null || request.getPassphrase().isBlank())) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .error("PASSPHRASE_REQUIRED")
                            .message("Bitget 交易所需要提供 API Passphrase")
                            .build());
        }

        // 3. 交易所切換檢查：OPEN + PENDING_CLOSE 都視為未收斂
        List<UserApiKey> existingKeys = userService.getApiKeys(userId);
        if (!existingKeys.isEmpty()) {
            UserApiKey existing = existingKeys.get(0);
            if (!existing.getExchange().equals(newExchange)) {
                long unsettledCount = tradeRepository.countByUserIdAndStatusIn(
                        userId, UNSETTLED_STATUSES);
                if (unsettledCount > 0) {
                    log.warn("用戶 {} 嘗試切換交易所 {} → {}，但有 {} 筆未收斂交易",
                            userId, existing.getExchange(), newExchange, unsettledCount);
                    return ResponseEntity.badRequest()
                            .body(ErrorResponse.builder()
                                    .error("EXCHANGE_SWITCH_BLOCKED")
                                    .message("切換交易所前請先平倉所有持倉（目前有 " + unsettledCount + " 筆未收斂交易）")
                                    .build());
                }
                log.info("用戶 {} 切換交易所 {} → {}（無未收斂交易，允許切換）",
                        userId, existing.getExchange(), newExchange);
            }
        }

        UserApiKey saved = userService.saveApiKey(
                userId, newExchange,
                request.getApiKey(), request.getSecretKey(),
                request.getPassphrase());

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
