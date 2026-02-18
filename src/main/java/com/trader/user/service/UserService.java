package com.trader.user.service;

import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.User;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserApiKeyRepository userApiKeyRepository;
    private final AesEncryptionUtil aesEncryptionUtil;

    public Optional<User> findById(String userId) {
        return userRepository.findById(userId);
    }

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
}
