package com.trader.service;

import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.User;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository userRepository;
    private UserApiKeyRepository userApiKeyRepository;
    private AesEncryptionUtil aesEncryptionUtil;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userApiKeyRepository = mock(UserApiKeyRepository.class);
        aesEncryptionUtil = mock(AesEncryptionUtil.class);
        userService = new UserService(userRepository, userApiKeyRepository, aesEncryptionUtil);
    }

    @Nested
    @DisplayName("儲存 API Key")
    class SaveApiKey {

        @Test
        @DisplayName("新 Key → 加密後存入 DB")
        void newKey_encryptsAndSaves() {
            when(userApiKeyRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());
            when(aesEncryptionUtil.encrypt("raw-api-key")).thenReturn("encrypted-api");
            when(aesEncryptionUtil.encrypt("raw-secret-key")).thenReturn("encrypted-secret");
            when(userApiKeyRepository.save(any(UserApiKey.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserApiKey result = userService.saveApiKey(
                    "user-1", "BINANCE", "raw-api-key", "raw-secret-key");

            assertThat(result.getUserId()).isEqualTo("user-1");
            assertThat(result.getExchange()).isEqualTo("BINANCE");
            assertThat(result.getEncryptedApiKey()).isEqualTo("encrypted-api");
            assertThat(result.getEncryptedSecretKey()).isEqualTo("encrypted-secret");
        }

        @Test
        @DisplayName("已存在 Key → upsert 更新")
        void existingKey_updatesInPlace() {
            UserApiKey existing = UserApiKey.builder()
                    .id(99L)
                    .userId("user-1")
                    .exchange("BINANCE")
                    .encryptedApiKey("old-encrypted-api")
                    .encryptedSecretKey("old-encrypted-secret")
                    .build();

            when(userApiKeyRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(existing));
            when(aesEncryptionUtil.encrypt("new-api-key")).thenReturn("new-encrypted-api");
            when(aesEncryptionUtil.encrypt("new-secret-key")).thenReturn("new-encrypted-secret");
            when(userApiKeyRepository.save(any(UserApiKey.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserApiKey result = userService.saveApiKey(
                    "user-1", "BINANCE", "new-api-key", "new-secret-key");

            // 驗證 id 保留（upsert 而非新建）
            assertThat(result.getId()).isEqualTo(99L);
            assertThat(result.getEncryptedApiKey()).isEqualTo("new-encrypted-api");
            assertThat(result.getEncryptedSecretKey()).isEqualTo("new-encrypted-secret");
        }

        @Test
        @DisplayName("明文不會存入 DB（ArgumentCaptor 驗證）")
        void plaintextNeverSavedToDb() {
            when(userApiKeyRepository.findByUserIdAndExchange(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(aesEncryptionUtil.encrypt("plain-api")).thenReturn("enc-api");
            when(aesEncryptionUtil.encrypt("plain-secret")).thenReturn("enc-secret");
            when(userApiKeyRepository.save(any(UserApiKey.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            userService.saveApiKey("user-1", "BINANCE", "plain-api", "plain-secret");

            ArgumentCaptor<UserApiKey> captor = ArgumentCaptor.forClass(UserApiKey.class);
            verify(userApiKeyRepository).save(captor.capture());
            UserApiKey saved = captor.getValue();

            assertThat(saved.getEncryptedApiKey()).doesNotContain("plain-api");
            assertThat(saved.getEncryptedSecretKey()).doesNotContain("plain-secret");
        }

        @Test
        @DisplayName("不同交易所 Key → 各自獨立")
        void differentExchanges_independent() {
            when(userApiKeyRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());
            when(userApiKeyRepository.findByUserIdAndExchange("user-1", "OKX"))
                    .thenReturn(Optional.empty());
            when(aesEncryptionUtil.encrypt(anyString())).thenReturn("encrypted");
            when(userApiKeyRepository.save(any(UserApiKey.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserApiKey binance = userService.saveApiKey(
                    "user-1", "BINANCE", "b-api", "b-secret");
            UserApiKey okx = userService.saveApiKey(
                    "user-1", "OKX", "o-api", "o-secret");

            assertThat(binance.getExchange()).isEqualTo("BINANCE");
            assertThat(okx.getExchange()).isEqualTo("OKX");

            // 各自呼叫一次 findByUserIdAndExchange
            verify(userApiKeyRepository).findByUserIdAndExchange("user-1", "BINANCE");
            verify(userApiKeyRepository).findByUserIdAndExchange("user-1", "OKX");
        }
    }

    @Nested
    @DisplayName("解密 Key")
    class DecryptKey {

        @Test
        @DisplayName("decryptApiKey → 回傳解密後明文")
        void decryptApiKey_returnsPlaintext() {
            UserApiKey apiKey = UserApiKey.builder()
                    .encryptedApiKey("enc-api-key")
                    .build();

            when(aesEncryptionUtil.decrypt("enc-api-key")).thenReturn("plain-api-key");

            String result = userService.decryptApiKey(apiKey);

            assertThat(result).isEqualTo("plain-api-key");
            verify(aesEncryptionUtil).decrypt("enc-api-key");
        }

        @Test
        @DisplayName("decryptSecretKey → 回傳解密後明文")
        void decryptSecretKey_returnsPlaintext() {
            UserApiKey apiKey = UserApiKey.builder()
                    .encryptedSecretKey("enc-secret-key")
                    .build();

            when(aesEncryptionUtil.decrypt("enc-secret-key")).thenReturn("plain-secret-key");

            String result = userService.decryptSecretKey(apiKey);

            assertThat(result).isEqualTo("plain-secret-key");
            verify(aesEncryptionUtil).decrypt("enc-secret-key");
        }
    }

    @Nested
    @DisplayName("查詢用戶")
    class FindUser {

        @Test
        @DisplayName("findById → 委派給 UserRepository")
        void findById_delegatesToRepository() {
            User user = User.builder().userId("u-1").email("a@b.com").build();
            when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

            Optional<User> result = userService.findById("u-1");

            assertThat(result).isPresent();
            assertThat(result.get().getEmail()).isEqualTo("a@b.com");
        }

        @Test
        @DisplayName("findByEmail → 委派給 UserRepository")
        void findByEmail_delegatesToRepository() {
            User user = User.builder().userId("u-1").email("a@b.com").build();
            when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

            Optional<User> result = userService.findByEmail("a@b.com");

            assertThat(result).isPresent();
            assertThat(result.get().getUserId()).isEqualTo("u-1");
        }

        @Test
        @DisplayName("getApiKeys → 回傳用戶所有 API Key")
        void getApiKeys_returnsList() {
            List<UserApiKey> keys = List.of(
                    UserApiKey.builder().exchange("BINANCE").build(),
                    UserApiKey.builder().exchange("OKX").build()
            );
            when(userApiKeyRepository.findByUserId("u-1")).thenReturn(keys);

            List<UserApiKey> result = userService.getApiKeys("u-1");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(UserApiKey::getExchange)
                    .containsExactly("BINANCE", "OKX");
        }
    }
}
