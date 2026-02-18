package com.trader.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * PUT /api/user/api-keys 回應
 */
@Data
@Builder
public class SaveApiKeyResponse {

    private String message;
    private String exchange;
    private String updatedAt;
}
