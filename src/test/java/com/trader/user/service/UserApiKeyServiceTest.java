package com.trader.user.service;

import com.trader.shared.exception.AesDecryptionException;
import com.trader.shared.exception.AesDecryptionException.ErrorType;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserApiKeyService 單元測試
 *
 * 覆蓋：正常解密、分類錯誤處理、批次查詢
 */
class UserApiKeyServiceTest {

    private UserApiKeyRepository userApiKeyRepository;
    private AesEncryptionUtil aesEncryptionUtil;
    private UserApiKeyService service;

    @BeforeEach
    void setUp() {
        userApiKeyRepository = mock(UserApiKeyRepository.class);
        aesEncryptionUtil = mock(AesEncryptionUtil.class);
        service = new UserApiKeyService(userApiKeyRepository, aesEncryptionUtil);
    }

    // ==================== getUserExchangeKeys ====================

    @Nested
    @DisplayName("getUserExchangeKeys")
    class GetUserExchangeKeysTests {

        @Test
        @DisplayName("用戶未設定 API Key → empty")
        void noApiKey_returnsEmpty() {
            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.empty());

            Optional<UserApiKeyService.ExchangeKeys> result = service.getUserExchangeKeys("user1", "BINANCE");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("API Key 不完整（encryptedSecretKey 為 null）→ empty")
        void incompleteKey_returnsEmpty() {
            UserApiKey entity = new UserApiKey();
            entity.setUserId("user1");
            entity.setEncryptedApiKey("encrypted-api");
            entity.setEncryptedSecretKey(null);

            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.of(entity));

            Optional<UserApiKeyService.ExchangeKeys> result = service.getUserExchangeKeys("user1", "BINANCE");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常解密 → 返回 ExchangeKeys")
        void normalDecrypt_returnsKeys() {
            UserApiKey entity = new UserApiKey();
            entity.setUserId("user1");
            entity.setEncryptedApiKey("enc-api");
            entity.setEncryptedSecretKey("enc-secret");

            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.of(entity));
            when(aesEncryptionUtil.decrypt("enc-api")).thenReturn("plain-api");
            when(aesEncryptionUtil.decrypt("enc-secret")).thenReturn("plain-secret");

            Optional<UserApiKeyService.ExchangeKeys> result = service.getUserExchangeKeys("user1", "BINANCE");

            assertThat(result).isPresent();
            assertThat(result.get().apiKey()).isEqualTo("plain-api");
            assertThat(result.get().secretKey()).isEqualTo("plain-secret");
        }

        @Test
        @DisplayName("AES 解密失敗 (AUTH_TAG_MISMATCH) → empty + 不影響系統")
        void authTagMismatch_returnsEmpty() {
            UserApiKey entity = new UserApiKey();
            entity.setUserId("user1");
            entity.setEncryptedApiKey("bad-enc");
            entity.setEncryptedSecretKey("enc-secret");

            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.of(entity));
            when(aesEncryptionUtil.decrypt("bad-enc"))
                    .thenThrow(new AesDecryptionException(
                            ErrorType.AUTH_TAG_MISMATCH, "GCM tag 不符", null));

            Optional<UserApiKeyService.ExchangeKeys> result = service.getUserExchangeKeys("user1", "BINANCE");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("AES 解密失敗 (DATA_CORRUPTED) → empty + 不影響系統")
        void dataCorrupted_returnsEmpty() {
            UserApiKey entity = new UserApiKey();
            entity.setUserId("user1");
            entity.setEncryptedApiKey("corrupted");
            entity.setEncryptedSecretKey("enc-secret");

            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.of(entity));
            when(aesEncryptionUtil.decrypt("corrupted"))
                    .thenThrow(new AesDecryptionException(
                            ErrorType.DATA_CORRUPTED, "Base64 損壞", null));

            Optional<UserApiKeyService.ExchangeKeys> result = service.getUserExchangeKeys("user1", "BINANCE");
            assertThat(result).isEmpty();
        }
    }

    // ==================== getAllExchangeKeys ====================

    @Nested
    @DisplayName("getAllExchangeKeys")
    class GetAllExchangeKeysTests {

        @Test
        @DisplayName("混合成功與失敗 — 只回傳成功的")
        void mixedResults_onlySuccessful() {
            UserApiKey good = new UserApiKey();
            good.setUserId("user-ok");
            good.setEncryptedApiKey("enc-ok-api");
            good.setEncryptedSecretKey("enc-ok-secret");

            UserApiKey bad = new UserApiKey();
            bad.setUserId("user-bad");
            bad.setEncryptedApiKey("enc-bad");
            bad.setEncryptedSecretKey("enc-bad-secret");

            UserApiKey incomplete = new UserApiKey();
            incomplete.setUserId("user-incomplete");
            incomplete.setEncryptedApiKey("enc-api");
            incomplete.setEncryptedSecretKey(null);

            when(userApiKeyRepository.findByExchange("BINANCE"))
                    .thenReturn(List.of(good, bad, incomplete));
            when(aesEncryptionUtil.decrypt("enc-ok-api")).thenReturn("api-ok");
            when(aesEncryptionUtil.decrypt("enc-ok-secret")).thenReturn("secret-ok");
            when(aesEncryptionUtil.decrypt("enc-bad"))
                    .thenThrow(new AesDecryptionException(
                            ErrorType.AUTH_TAG_MISMATCH, "key mismatch", null));

            Map<String, UserApiKeyService.ExchangeKeys> result = service.getAllExchangeKeys("BINANCE");

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("user-ok");
            assertThat(result.get("user-ok").apiKey()).isEqualTo("api-ok");
        }
    }

    // ==================== hasApiKey / getUserIdsWithApiKey ====================

    @Nested
    @DisplayName("輔助方法")
    class HelperTests {

        @Test
        @DisplayName("hasApiKey — 有 → true")
        void hasApiKey_true() {
            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.of(new UserApiKey()));
            assertThat(service.hasApiKey("user1", "BINANCE")).isTrue();
        }

        @Test
        @DisplayName("hasApiKey — 沒有 → false")
        void hasApiKey_false() {
            when(userApiKeyRepository.findByUserIdAndExchange("user1", "BINANCE"))
                    .thenReturn(Optional.empty());
            assertThat(service.hasApiKey("user1", "BINANCE")).isFalse();
        }

        @Test
        @DisplayName("getUserIdsWithApiKey — 返回 userId Set")
        void getUserIdsWithApiKey() {
            when(userApiKeyRepository.findUserIdsByExchange("BINANCE"))
                    .thenReturn(List.of("user1", "user2"));

            assertThat(service.getUserIdsWithApiKey("BINANCE"))
                    .containsExactlyInAnyOrder("user1", "user2");
        }
    }
}
