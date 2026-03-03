package com.trader.user.service;

import com.trader.shared.exception.AesDecryptionException;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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
        } catch (AesDecryptionException e) {
            log.error("用戶 {} API Key 解密失敗 [{}]: {}", userId, e.getErrorType(), e.getMessage());
            return Optional.empty();
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

    // ==================== Batch 方法（避免 N+1）====================

    /**
     * Batch 查詢：取得所有擁有指定交易所 API Key 的 userId Set
     * 一次 SQL 查詢，O(1) 查找
     *
     * @param exchange 交易所名稱
     * @return 擁有 API Key 的 userId 集合
     */
    public Set<String> getUserIdsWithApiKey(String exchange) {
        return new HashSet<>(userApiKeyRepository.findUserIdsByExchange(exchange));
    }

    /**
     * Batch 查詢 + 解密：取得指定交易所的所有用戶 API Key（解密後）
     * 一次 SQL 查詢 + 批量解密，避免 hasApiKey + getUserBinanceKeys 的 dual lookup
     *
     * @param exchange 交易所名稱
     * @return userId → BinanceKeys 的 Map，只包含解密成功且完整的記錄
     */
    public Map<String, BinanceKeys> getAllBinanceKeys(String exchange) {
        List<UserApiKey> allKeys = userApiKeyRepository.findByExchange(exchange);
        Map<String, BinanceKeys> result = new HashMap<>();

        for (UserApiKey entity : allKeys) {
            if (entity.getEncryptedApiKey() == null || entity.getEncryptedSecretKey() == null) {
                log.warn("用戶 {} 的 {} API Key 不完整，跳過", entity.getUserId(), exchange);
                continue;
            }
            try {
                String apiKey = aesEncryptionUtil.decrypt(entity.getEncryptedApiKey());
                String secretKey = aesEncryptionUtil.decrypt(entity.getEncryptedSecretKey());
                result.put(entity.getUserId(), new BinanceKeys(apiKey, secretKey));
            } catch (AesDecryptionException e) {
                log.error("用戶 {} API Key 解密失敗 [{}]: {}", entity.getUserId(), e.getErrorType(), e.getMessage());
            } catch (Exception e) {
                log.error("用戶 {} API Key 解密失敗: {}", entity.getUserId(), e.getMessage());
            }
        }

        return result;
    }
}
