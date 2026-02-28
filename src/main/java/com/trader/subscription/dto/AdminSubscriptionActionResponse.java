package com.trader.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin 訂閱操作回應（開通/取消/設定終生免費）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSubscriptionActionResponse {

    private String userId;
    private String planId;
    private String status;
    private LocalDateTime currentPeriodEnd;  // LIFETIME 時為 null
    private String message;
}
