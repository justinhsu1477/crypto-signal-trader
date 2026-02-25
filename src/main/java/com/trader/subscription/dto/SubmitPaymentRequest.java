package com.trader.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用戶提交 USDT 付款 txHash 請求
 */
@Data
public class SubmitPaymentRequest {

    /** 方案 ID */
    @NotBlank(message = "方案 ID 不可為空")
    private String planId;

    /** 鏈上交易 Hash */
    @NotBlank(message = "交易 Hash 不可為空")
    private String txHash;
}
