package com.trader.user.controller;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.SecurityUtil;
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
     * @return {@link SaveApiKeyResponse}
     */
    @PutMapping("/api-keys")
    public ResponseEntity<?> saveApiKeys(@Valid @RequestBody SaveApiKeyRequest request) {
        String userId = SecurityUtil.getCurrentUserId();

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
}
