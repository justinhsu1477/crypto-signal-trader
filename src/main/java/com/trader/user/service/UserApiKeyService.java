package com.trader.user.service;

import com.trader.shared.exception.AesDecryptionException;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用戶 API Key 服務
 *
 * 負責從 DB 取得用戶的交易所 API Key 並解密，
 * 供廣播跟單、排程任務等場景使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserApiKeyService {

    private final UserApiKeyRepository userApiKeyRepository;
    private final AesEncryptionUtil aesEncryptionUtil;

    /**
     * 解密後的交易所 API Key 對（通用於所有交易所）
     */
    public record ExchangeKeys(String apiKey, String secretKey) {}

    /**
     * 取得用戶指定交易所的 API Key（解密後）
     *
     * @param userId   用戶 ID
     * @param exchange 交易所名稱（BINANCE / BYBIT）
     * @return 解密後的 apiKey + secretKey，若用戶未設定則返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<ExchangeKeys> getUserExchangeKeys(String userId, String exchange) {
        Optional<UserApiKey> keyOpt = userApiKeyRepository
                .findByUserIdAndExchange(userId, exchange);

        if (keyOpt.isEmpty()) {
            log.debug("用戶 {} 未設定 {} API Key", userId, exchange);
            return Optional.empty();
        }

        UserApiKey entity = keyOpt.get();
        if (entity.getEncryptedApiKey() == null || entity.getEncryptedSecretKey() == null) {
            log.warn("用戶 {} 的 {} API Key 不完整", userId, exchange);
            return Optional.empty();
        }

        try {
            String apiKey = aesEncryptionUtil.decrypt(entity.getEncryptedApiKey());
            String secretKey = aesEncryptionUtil.decrypt(entity.getEncryptedSecretKey());
            return Optional.of(new ExchangeKeys(apiKey, secretKey));
        } catch (AesDecryptionException e) {
            log.error("用戶 {} {} API Key 解密失敗 [{}]: {}", userId, exchange, e.getErrorType(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("用戶 {} {} API Key 解密失敗: {}", userId, exchange, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 檢查用戶是否已設定指定交易所的 API Key
     */
    @Transactional(readOnly = true)
    public boolean hasApiKey(String userId, String exchange) {
        return userApiKeyRepository.findByUserIdAndExchange(userId, exchange).isPresent();
    }

    // ==================== Batch 方法（避免 N+1）====================

    /**
     * Batch 查詢：取得所有擁有指定交易所 API Key 的 userId Set
     * 一次 SQL 查詢，O(1) 查找
     *
     * @param exchange 交易所名稱
     * @return 擁有 API Key 的 userId 集合
     */
    @Transactional(readOnly = true)
    public Set<String> getUserIdsWithApiKey(String exchange) {
        return new HashSet<>(userApiKeyRepository.findUserIdsByExchange(exchange));
    }

    /**
     * Batch 查詢 + 解密：取得指定交易所的所有用戶 API Key（解密後）
     * 一次 SQL 查詢 + 批量解密，避免 hasApiKey + getUserExchangeKeys 的 dual lookup
     *
     * @param exchange 交易所名稱
     * @return userId → ExchangeKeys 的 Map，只包含解密成功且完整的記錄
     */
    @Transactional(readOnly = true)
    public Map<String, ExchangeKeys> getAllExchangeKeys(String exchange) {
        List<UserApiKey> allKeys = userApiKeyRepository.findByExchange(exchange);
        Map<String, ExchangeKeys> result = new HashMap<>();

        for (UserApiKey entity : allKeys) {
            if (entity.getEncryptedApiKey() == null || entity.getEncryptedSecretKey() == null) {
                log.warn("用戶 {} 的 {} API Key 不完整，跳過", entity.getUserId(), exchange);
                continue;
            }
            try {
                String apiKey = aesEncryptionUtil.decrypt(entity.getEncryptedApiKey());
                String secretKey = aesEncryptionUtil.decrypt(entity.getEncryptedSecretKey());
                result.put(entity.getUserId(), new ExchangeKeys(apiKey, secretKey));
            } catch (AesDecryptionException e) {
                log.error("用戶 {} API Key 解密失敗 [{}]: {}", entity.getUserId(), e.getErrorType(), e.getMessage());
            } catch (Exception e) {
                log.error("用戶 {} API Key 解密失敗: {}", entity.getUserId(), e.getMessage());
            }
        }

        return result;
    }

    /**
     * 取得用戶主要交易所的 API Key（BINANCE 優先）
     *
     * 依序嘗試 BINANCE → BYBIT，返回第一個找到的交易所及其解密後的 Key。
     * 供排程任務決定用戶使用哪個交易所查餘額/持倉。
     *
     * @param userId 用戶 ID
     * @return (exchange, keys) pair，若用戶未設定任何交易所 Key 則返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<Map.Entry<String, ExchangeKeys>> getUserPrimaryExchangeKeys(String userId) {
        for (String exchange : List.of("BINANCE", "BYBIT")) {
            Optional<ExchangeKeys> keys = getUserExchangeKeys(userId, exchange);
            if (keys.isPresent()) {
                return Optional.of(Map.entry(exchange, keys.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * 取得所有用戶的交易所配對（用於廣播跟單路由）
     *
     * @return userId → 該用戶已設定 API Key 的交易所集合
     */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> getUserExchangeMap() {
        List<UserApiKey> allKeys = userApiKeyRepository.findAll();
        Map<String, Set<String>> result = new HashMap<>();
        for (UserApiKey key : allKeys) {
            if (key.getEncryptedApiKey() != null && key.getEncryptedSecretKey() != null) {
                result.computeIfAbsent(key.getUserId(), k -> new HashSet<>()).add(key.getExchange());
            }
        }
        return result;
    }
}
