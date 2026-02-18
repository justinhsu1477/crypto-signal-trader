package com.trader.subscription.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 通用訊息回應（用於 TODO 佔位端點）
 */
@Data
@Builder
public class MessageResponse {

    private String status;
    private String message;
}
