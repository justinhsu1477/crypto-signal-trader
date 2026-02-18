package com.trader.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * GET /api/user/api-keys 回應項目
 *
 * 只回傳 metadata，絕不回傳真實 key
 */
@Data
@Builder
public class ApiKeyMetadata {

    /** 交易所名稱 */
    private String exchange;

    /** 是否已設定 API Key */
    private boolean hasApiKey;

    /** 最後更新時間 */
    private String updatedAt;
}
