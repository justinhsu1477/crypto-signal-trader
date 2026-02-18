package com.trader.shared.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
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

    @Value("${encryption.aes-key}")
    private String aesKey;

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
        } catch (Exception e) {
            throw new RuntimeException("解密失敗", e);
        }
    }
}
