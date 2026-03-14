package com.trader.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API Key 健康檢查結果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyHealthResponse {
    private boolean valid;
    private String exchange;
    private String message;
    private boolean canTrade;          // 是否具有交易權限
    private boolean futuresEnabled;    // 是否開通合約
}
