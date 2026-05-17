package com.trader.service;

import com.trader.shared.exception.AesDecryptionException;
import com.trader.shared.exception.AesDecryptionException.ErrorType;
import com.trader.shared.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class AesEncryptionUtilTest {

    private static final String TEST_AES_KEY = "01234567890123456789012345678901"; // exactly 32 chars

    private AesEncryptionUtil aesEncryptionUtil;

    @BeforeEach
    void setUp() {
        aesEncryptionUtil = new AesEncryptionUtil(TEST_AES_KEY);
    }

    @Nested
    @DisplayName("加密解密 Roundtrip")
    class Roundtrip {

        @Test
        @DisplayName("一般文字 → encrypt → decrypt = 原始明文")
        void normalText_roundtrip() {
            String plaintext = "my-api-key-12345";

            String encrypted = aesEncryptionUtil.encrypt(plaintext);
            String decrypted = aesEncryptionUtil.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("空字串 → encrypt → decrypt = 空字串")
        void emptyString_roundtrip() {
            String plaintext = "";

            String encrypted = aesEncryptionUtil.encrypt(plaintext);
            String decrypted = aesEncryptionUtil.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("長字串 → encrypt → decrypt = 原始明文")
        void longString_roundtrip() {
            String plaintext = "a".repeat(1000);

            String encrypted = aesEncryptionUtil.encrypt(plaintext);
            String decrypted = aesEncryptionUtil.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("特殊字元（中文、emoji）→ encrypt → decrypt = 原始明文")
        void specialChars_roundtrip() {
            String plaintext = "密鑰測試！@#$%^&*()🚀";

            String encrypted = aesEncryptionUtil.encrypt(plaintext);
            String decrypted = aesEncryptionUtil.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("Python AES-GCM 加密 → Java decrypt — cross-platform 相容驗證")
        void pythonGeneratedCiphertext_javaDecrypts() {
            // 此測試保證 discord-monitor/scripts/encrypt_mirror_webhooks.py 的 Python AESGCM
            // 輸出格式跟 Java AesEncryptionUtil 互通 — Python 加密的 ciphertext，Java 必須能解。
            //
            // 此 ciphertext 由以下 Python 命令產生（key = 32 chars "01234567890123456789012345678901"）：
            //
            //   ENCRYPTION_AES_KEY="01234567890123456789012345678901" python3 -c "
            //   from encrypt_mirror_webhooks import encrypt_aes_gcm
            //   print(encrypt_aes_gcm('https://discord.com/api/webhooks/123456789/test-token-AbCdEf',
            //                          '01234567890123456789012345678901'))"
            //
            // 若此測試 fail，表示 Python script 跟 Java 格式跑掉了，user 跑 SQL 後資料會解不開。
            String pythonEncrypted = "ZBh+KE5EE7fre8jca/wlpsYNxLMsScUizzjB4OyP0zZDnkY0JQV5gIDzUHDnISkejWDBParVi3X84zJ/8H0O8D4lxJy5MxbIpTNLExFmRN00dzWw9eLtBA==";
            String expectedPlaintext = "https://discord.com/api/webhooks/123456789/test-token-AbCdEf";

            String decrypted = aesEncryptionUtil.decrypt(pythonEncrypted);

            assertThat(decrypted)
                    .as("Python AESGCM ciphertext 應該能被 Java AES/GCM/NoPadding 解開")
                    .isEqualTo(expectedPlaintext);
        }
    }

    @Nested
    @DisplayName("隨機 IV")
    class RandomIv {

        @Test
        @DisplayName("同一明文加密兩次 → 密文不同")
        void samePlaintext_differentCiphertext() {
            String plaintext = "same-api-key";

            String encrypted1 = aesEncryptionUtil.encrypt(plaintext);
            String encrypted2 = aesEncryptionUtil.encrypt(plaintext);

            assertThat(encrypted1).isNotEqualTo(encrypted2);
        }

        @Test
        @DisplayName("不同密文解密後 → 同一明文")
        void differentCiphertext_samePlaintext() {
            String plaintext = "same-api-key";

            String encrypted1 = aesEncryptionUtil.encrypt(plaintext);
            String encrypted2 = aesEncryptionUtil.encrypt(plaintext);

            assertThat(aesEncryptionUtil.decrypt(encrypted1)).isEqualTo(plaintext);
            assertThat(aesEncryptionUtil.decrypt(encrypted2)).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("篡改偵測")
    class TamperDetection {

        @Test
        @DisplayName("修改密文 byte → 解密拋出 AesDecryptionException (AUTH_TAG_MISMATCH)")
        void tamperedCiphertext_throwsException() {
            String encrypted = aesEncryptionUtil.encrypt("secret-key");
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            // 篡改最後一個 byte
            decoded[decoded.length - 1] ^= 0xFF;
            String tampered = Base64.getEncoder().encodeToString(decoded);

            assertThatThrownBy(() -> aesEncryptionUtil.decrypt(tampered))
                    .isInstanceOf(AesDecryptionException.class)
                    .satisfies(e -> assertThat(((AesDecryptionException) e).getErrorType())
                            .isEqualTo(ErrorType.AUTH_TAG_MISMATCH));
        }

        @Test
        @DisplayName("垃圾 Base64 字串 → 解密拋出 AesDecryptionException (DATA_CORRUPTED)")
        void garbageBase64_throwsException() {
            String garbage = Base64.getEncoder().encodeToString("short".getBytes());

            assertThatThrownBy(() -> aesEncryptionUtil.decrypt(garbage))
                    .isInstanceOf(AesDecryptionException.class)
                    .satisfies(e -> assertThat(((AesDecryptionException) e).getErrorType())
                            .isEqualTo(ErrorType.DATA_CORRUPTED));
        }

        @Test
        @DisplayName("非 Base64 字串 → 解密拋出 AesDecryptionException (DATA_CORRUPTED)")
        void invalidBase64_throwsDataCorrupted() {
            assertThatThrownBy(() -> aesEncryptionUtil.decrypt("!!!not-base64!!!"))
                    .isInstanceOf(AesDecryptionException.class)
                    .satisfies(e -> assertThat(((AesDecryptionException) e).getErrorType())
                            .isEqualTo(ErrorType.DATA_CORRUPTED));
        }

        @Test
        @DisplayName("用不同 key 解密 → AUTH_TAG_MISMATCH")
        void wrongKey_throwsAuthTagMismatch() {
            String encrypted = aesEncryptionUtil.encrypt("my-secret");

            // 用不同的 key 建構新的 util
            AesEncryptionUtil wrongKeyUtil = new AesEncryptionUtil("98765432109876543210987654321098");

            assertThatThrownBy(() -> wrongKeyUtil.decrypt(encrypted))
                    .isInstanceOf(AesDecryptionException.class)
                    .satisfies(e -> assertThat(((AesDecryptionException) e).getErrorType())
                            .isEqualTo(ErrorType.AUTH_TAG_MISMATCH));
        }

        @Test
        @DisplayName("AesDecryptionException 包含 errorType getter")
        void exceptionContainsErrorType() {
            AesDecryptionException ex = new AesDecryptionException(
                    ErrorType.INVALID_KEY, "test", null);
            assertThat(ex.getErrorType()).isEqualTo(ErrorType.INVALID_KEY);
            assertThat(ex.getMessage()).isEqualTo("test");
        }
    }

    @Nested
    @DisplayName("輸出格式")
    class OutputFormat {

        @Test
        @DisplayName("密文為有效 Base64 格式")
        void encrypted_isValidBase64() {
            String encrypted = aesEncryptionUtil.encrypt("test-key");

            assertThatCode(() -> Base64.getDecoder().decode(encrypted))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("密文 decode 後長度 > 12 bytes（至少含 IV）")
        void encrypted_decodedLengthGreaterThan12() {
            String encrypted = aesEncryptionUtil.encrypt("test-key");
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            // 12 bytes IV + ciphertext + 16 bytes auth tag
            assertThat(decoded.length).isGreaterThan(28);
        }
    }
}
