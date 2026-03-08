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
     * passphrase 為 nullable，僅 Bitget 使用。
     */
    public record ExchangeKeys(String apiKey, String secretKey, String passphrase) {
        /** Binance / Bybit 用（向後相容，passphrase = null） */
        public ExchangeKeys(String apiKey, String secretKey) {
            this(apiKey, secretKey, null);
        }
    }

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
            String passphrase = decryptPassphraseIfPresent(entity);
            return Optional.of(new ExchangeKeys(apiKey, secretKey, passphrase));
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
                String passphrase = decryptPassphraseIfPresent(entity);
                result.put(entity.getUserId(), new ExchangeKeys(apiKey, secretKey, passphrase));
            } catch (AesDecryptionException e) {
                log.error("用戶 {} API Key 解密失敗 [{}]: {}", entity.getUserId(), e.getErrorType(), e.getMessage());
            } catch (Exception e) {
                log.error("用戶 {} API Key 解密失敗: {}", entity.getUserId(), e.getMessage());
            }
        }

        return result;
    }

    /**
     * 取得用戶唯一交易所的 API Key（一用戶一交易所）
     *
     * 設計：V31 UNIQUE(user_id) 約束確保每個用戶最多一筆 API Key。
     * 直接查 findByUserId() 取唯一記錄，不再按交易所優先順序遍歷。
     *
     * @param userId 用戶 ID
     * @return (exchange, keys) pair，若用戶未設定 Key 則返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<Map.Entry<String, ExchangeKeys>> getUserPrimaryExchangeKeys(String userId) {
        List<UserApiKey> keys = userApiKeyRepository.findByUserId(userId);
        if (keys.isEmpty()) {
            log.debug("用戶 {} 未設定任何 API Key", userId);
            return Optional.empty();
        }

        UserApiKey entity = keys.get(0); // UNIQUE(user_id) 約束確保最多一筆
        if (entity.getEncryptedApiKey() == null || entity.getEncryptedSecretKey() == null) {
            log.warn("用戶 {} 的 {} API Key 不完整", userId, entity.getExchange());
            return Optional.empty();
        }

        try {
            String apiKey = aesEncryptionUtil.decrypt(entity.getEncryptedApiKey());
            String secretKey = aesEncryptionUtil.decrypt(entity.getEncryptedSecretKey());
            String passphrase = decryptPassphraseIfPresent(entity);
            return Optional.of(Map.entry(entity.getExchange(), new ExchangeKeys(apiKey, secretKey, passphrase)));
        } catch (AesDecryptionException e) {
            log.error("用戶 {} API Key 解密失敗 [{}]: {}", userId, e.getErrorType(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("用戶 {} API Key 解密失敗: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 取得所有用戶的交易所對應（一用戶一交易所）
     *
     * 用於廣播跟單路由和啟動對帳。
     * V31 UNIQUE(user_id) 約束確保每個用戶最多一筆記錄。
     *
     * @return userId → exchange 名稱
     */
    @Transactional(readOnly = true)
    public Map<String, String> getUserIdExchangeMap() {
        List<UserApiKey> allKeys = userApiKeyRepository.findAll();
        Map<String, String> result = new HashMap<>();
        for (UserApiKey key : allKeys) {
            if (key.getEncryptedApiKey() != null && key.getEncryptedSecretKey() != null) {
                result.put(key.getUserId(), key.getExchange());
            }
        }
        return result;
    }

    // ==================== Private Helper ====================

    /**
     * 解密 passphrase（若存在）。僅 Bitget 用戶會有值，其他交易所返回 null。
     */
    private String decryptPassphraseIfPresent(UserApiKey entity) {
        if (entity.getEncryptedPassphrase() == null || entity.getEncryptedPassphrase().isBlank()) {
            return null;
        }
        return aesEncryptionUtil.decrypt(entity.getEncryptedPassphrase());
    }
}
