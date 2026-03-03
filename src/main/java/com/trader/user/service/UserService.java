package com.trader.user.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.User;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserApiKeyRepository userApiKeyRepository;
    private final UserDiscordWebhookRepository userDiscordWebhookRepository;
    private final UserLineBindingRepository userLineBindingRepository;
    private final LineLinkingCodeRepository lineLinkingCodeRepository;
    private final UserNotificationPreferencesRepository userNotificationPreferencesRepository;
    private final UserTradeSettingsRepository userTradeSettingsRepository;
    private final AesEncryptionUtil aesEncryptionUtil;

    @Transactional(readOnly = true)
    public Optional<User> findById(String userId) {
        return userRepository.findById(userId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * 儲存或更新用戶的交易所 API Key（AES-256-GCM 加密後存入 DB）
     */
    @Transactional
    public UserApiKey saveApiKey(String userId, String exchange,
                                 String apiKey, String secretKey) {
        String encryptedApiKey = aesEncryptionUtil.encrypt(apiKey);
        String encryptedSecretKey = aesEncryptionUtil.encrypt(secretKey);

        UserApiKey entity = userApiKeyRepository
                .findByUserIdAndExchange(userId, exchange)
                .orElse(UserApiKey.builder()
                        .userId(userId)
                        .exchange(exchange)
                        .build());

        entity.setEncryptedApiKey(encryptedApiKey);
        entity.setEncryptedSecretKey(encryptedSecretKey);

        log.info("API Key 已加密儲存: userId={}, exchange={}", userId, exchange);
        return userApiKeyRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<UserApiKey> getApiKeys(String userId) {
        return userApiKeyRepository.findByUserId(userId);
    }

    /**
     * 解密 API Key（內部使用，供交易服務呼叫，不暴露於 API）
     */
    public String decryptApiKey(UserApiKey apiKey) {
        return aesEncryptionUtil.decrypt(apiKey.getEncryptedApiKey());
    }

    /**
     * 解密 Secret Key（內部使用，供交易服務呼叫，不暴露於 API）
     */
    public String decryptSecretKey(UserApiKey apiKey) {
        return aesEncryptionUtil.decrypt(apiKey.getEncryptedSecretKey());
    }

    /**
     * GDPR 帳號刪除 — 軟刪除 + PII 匿名化
     *
     * 1. 停用帳號（enabled = false）
     * 2. 匿名化 PII（email、name、passwordHash）
     * 3. 關閉自動跟單
     * 4. 清除所有敏感關聯資料（API Key、Webhook、LINE 綁定、通知偏好、交易設定）
     *
     * @param userId 要刪除的用戶 ID
     * @throws IllegalArgumentException 若用戶不存在
     */
    @Transactional
    public void deleteAccount(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在: " + userId));

        log.info("開始帳號刪除流程: userId={}, email={}", userId, user.getEmail());

        // 1. 匿名化 PII
        String anonymizedEmail = "deleted_" + UUID.randomUUID().toString().substring(0, 8) + "@deleted.com";
        user.setEmail(anonymizedEmail);
        user.setName("Deleted User");
        user.setPasswordHash("ACCOUNT_DELETED");

        // 2. 停用帳號 + 關閉所有功能
        user.setEnabled(false);
        user.setAutoTradeEnabled(false);
        user.setDiscordNotificationEnabled(false);
        user.setLineNotificationEnabled(false);
        user.setEmailVerified(false);
        user.setUpdatedAt(LocalDateTime.now(AppConstants.ZONE_ID));

        userRepository.save(user);

        // 3. 清除所有敏感關聯資料
        userApiKeyRepository.deleteByUserId(userId);
        userDiscordWebhookRepository.deleteByUserId(userId);
        userLineBindingRepository.deleteByUserId(userId);
        lineLinkingCodeRepository.deleteByUserId(userId);
        userNotificationPreferencesRepository.deleteById(userId);
        userTradeSettingsRepository.deleteById(userId);

        log.info("帳號刪除完成: userId={}, anonymizedEmail={}", userId, anonymizedEmail);
    }
}
