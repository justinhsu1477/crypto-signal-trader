package com.trader.shared.exception;

import lombok.Getter;

/**
 * AES 解密失敗的分類例外
 *
 * 根據底層 crypto 異常分類錯誤原因，方便排查：
 * - DATA_CORRUPTED: 密文格式損壞（Base64 解碼失敗、長度不足）
 * - AUTH_TAG_MISMATCH: GCM 認證標籤不符（密鑰錯誤 或 密文被篡改）
 * - INVALID_KEY: 加密金鑰格式不正確
 * - UNKNOWN: 其他未預期錯誤
 */
@Getter
public class AesDecryptionException extends RuntimeException {

    public enum ErrorType {
        DATA_CORRUPTED,       // Base64 損壞、密文長度不足
        AUTH_TAG_MISMATCH,    // GCM tag 驗證失敗（最常見：AES key 不對）
        INVALID_KEY,          // SecretKeySpec 建構失敗
        UNKNOWN               // 其他
    }

    private final ErrorType errorType;

    public AesDecryptionException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

}
