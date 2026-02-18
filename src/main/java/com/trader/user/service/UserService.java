package com.trader.user.service;

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

    public Optional<User> findById(String userId) {
        return userRepository.findById(userId);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * 儲存或更新用戶的交易所 API Key（加密後存入 DB）
     *
     * TODO: 實作 AES 加密邏輯
     */
    @Transactional
    public UserApiKey saveApiKey(String userId, String exchange,
                                 String apiKey, String secretKey) {
        // TODO: AES 加密 apiKey 和 secretKey
        String encryptedApiKey = apiKey;       // TODO: encrypt
        String encryptedSecretKey = secretKey;  // TODO: encrypt

        UserApiKey entity = userApiKeyRepository
                .findByUserIdAndExchange(userId, exchange)
                .orElse(UserApiKey.builder()
                        .userId(userId)
                        .exchange(exchange)
                        .build());

        entity.setEncryptedApiKey(encryptedApiKey);
        entity.setEncryptedSecretKey(encryptedSecretKey);

        return userApiKeyRepository.save(entity);
    }

    public List<UserApiKey> getApiKeys(String userId) {
        return userApiKeyRepository.findByUserId(userId);
    }
}
