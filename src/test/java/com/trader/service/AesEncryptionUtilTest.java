package com.trader.service;

import com.trader.shared.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class AesEncryptionUtilTest {

    private static final String TEST_AES_KEY = "01234567890123456789012345678901"; // exactly 32 chars

    private AesEncryptionUtil aesEncryptionUtil;

    @BeforeEach
    void setUp() throws Exception {
        aesEncryptionUtil = new AesEncryptionUtil();
        Field field = AesEncryptionUtil.class.getDeclaredField("aesKey");
        field.setAccessible(true);
        field.set(aesEncryptionUtil, TEST_AES_KEY);
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
        @DisplayName("修改密文 byte → 解密拋出 RuntimeException")
        void tamperedCiphertext_throwsException() {
            String encrypted = aesEncryptionUtil.encrypt("secret-key");
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            // 篡改最後一個 byte
            decoded[decoded.length - 1] ^= 0xFF;
            String tampered = Base64.getEncoder().encodeToString(decoded);

            assertThatThrownBy(() -> aesEncryptionUtil.decrypt(tampered))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("解密失敗");
        }

        @Test
        @DisplayName("垃圾 Base64 字串 → 解密拋出 RuntimeException")
        void garbageBase64_throwsException() {
            String garbage = Base64.getEncoder().encodeToString("short".getBytes());

            assertThatThrownBy(() -> aesEncryptionUtil.decrypt(garbage))
                    .isInstanceOf(RuntimeException.class);
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
