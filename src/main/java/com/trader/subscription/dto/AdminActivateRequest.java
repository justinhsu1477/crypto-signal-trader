package com.trader.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin 手動開通/延長訂閱 Request
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminActivateRequest {

    @NotBlank(message = "方案 ID 不可為空")
    private String planId;

    /** 訂閱天數，預設 30 天 */
    private Integer days;
}
