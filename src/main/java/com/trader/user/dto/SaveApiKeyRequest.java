package com.trader.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * PUT /api/user/api-keys 請求
 *
 * 儲存用戶交易所 API Key（將以 AES-256-GCM 加密後存入 DB）
 */
@Data
public class SaveApiKeyRequest {

    /** 交易所名稱（如 BINANCE、OKX） */
    @NotBlank(message = "exchange 不可為空")
    private String exchange;

    @NotBlank(message = "apiKey 不可為空")
    private String apiKey;

    @NotBlank(message = "secretKey 不可為空")
    private String secretKey;
}
