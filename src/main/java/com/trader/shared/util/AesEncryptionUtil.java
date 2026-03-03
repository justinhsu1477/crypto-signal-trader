package com.trader.shared.util;

import com.trader.shared.exception.AesDecryptionException;
import com.trader.shared.exception.AesDecryptionException.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密工具
 *
 * 用於加密用戶的交易所 API Key。
 * 輸出格式: Base64(IV + ciphertext + authTag)
 * 每次加密使用隨機 IV，同一明文會產生不同密文。
 */
@Slf4j
@Component
public class AesEncryptionUtil {

    private static final int GCM_IV_LENGTH = 12;     // 96 bits
    private static final int GCM_TAG_LENGTH = 128;   // 128 bits

    private final String aesKey;

    public AesEncryptionUtil(@Value("${encryption.aes-key}") String aesKey) {
        this.aesKey = aesKey;
    }

    /**
     * AES-256-GCM 加密
     *
     * @param plaintext 明文
     * @return Base64 編碼的密文（含 IV）
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            SecretKeySpec keySpec = new SecretKeySpec(
                    aesKey.getBytes(), 0, 32, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("加密失敗", e);
        }
    }

    /**
     * AES-256-GCM 解密
     *
     * @param encrypted Base64 編碼的密文
     * @return 明文
     */
    public String decrypt(String encrypted) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            if (decoded.length <= GCM_IV_LENGTH) {
                throw new AesDecryptionException(
                        ErrorType.DATA_CORRUPTED,
                        "密文長度不足（至少需要 IV " + GCM_IV_LENGTH + " bytes，實際 " + decoded.length + " bytes）",
                        null);
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            SecretKeySpec keySpec = new SecretKeySpec(
                    aesKey.getBytes(), 0, 32, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            return new String(cipher.doFinal(ciphertext));
        } catch (AesDecryptionException e) {
            throw e; // 已分類，直接拋出
        } catch (IllegalArgumentException e) {
            throw new AesDecryptionException(
                    ErrorType.DATA_CORRUPTED,
                    "Base64 解碼失敗，密文格式損壞",
                    e);
        } catch (AEADBadTagException e) {
            throw new AesDecryptionException(
                    ErrorType.AUTH_TAG_MISMATCH,
                    "GCM 認證標籤不符（可能原因：AES Key 不正確 或密文被篡改）",
                    e);
        } catch (InvalidKeyException e) {
            throw new AesDecryptionException(
                    ErrorType.INVALID_KEY,
                    "加密金鑰格式不正確",
                    e);
        } catch (Exception e) {
            throw new AesDecryptionException(
                    ErrorType.UNKNOWN,
                    "解密失敗: " + e.getMessage(),
                    e);
        }
    }
}
