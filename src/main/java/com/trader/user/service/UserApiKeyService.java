package com.trader.user.service;

import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 用戶 API Key 服務
 *
 * 負責從 DB 取得用戶的交易所 API Key 並解密，
 * 供 BinanceFuturesService 在廣播跟單時使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserApiKeyService {

    private final UserApiKeyRepository userApiKeyRepository;
    private final AesEncryptionUtil aesEncryptionUtil;

    /**
     * 解密後的 API Key 對
     */
    public record BinanceKeys(String apiKey, String secretKey) {}

    /**
     * 取得用戶的 Binance API Key（解密後）
     *
     * @param userId 用戶 ID
     * @return 解密後的 apiKey + secretKey，若用戶未設定則返回 empty
     */
    public Optional<BinanceKeys> getUserBinanceKeys(String userId) {
        Optional<UserApiKey> keyOpt = userApiKeyRepository
                .findByUserIdAndExchange(userId, "BINANCE");

        if (keyOpt.isEmpty()) {
            log.debug("用戶 {} 未設定 Binance API Key", userId);
            return Optional.empty();
        }

        UserApiKey entity = keyOpt.get();
        if (entity.getEncryptedApiKey() == null || entity.getEncryptedSecretKey() == null) {
            log.warn("用戶 {} 的 Binance API Key 不完整", userId);
            return Optional.empty();
        }

        try {
            String apiKey = aesEncryptionUtil.decrypt(entity.getEncryptedApiKey());
            String secretKey = aesEncryptionUtil.decrypt(entity.getEncryptedSecretKey());
            return Optional.of(new BinanceKeys(apiKey, secretKey));
        } catch (Exception e) {
            log.error("用戶 {} API Key 解密失敗: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 檢查用戶是否已設定 Binance API Key
     */
    public boolean hasApiKey(String userId) {
        return userApiKeyRepository.findByUserIdAndExchange(userId, "BINANCE").isPresent();
    }
}
