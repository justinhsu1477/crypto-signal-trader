package com.trader.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 升級/降級方案請求
 */
@Data
public class UpgradePlanRequest {

    /** 目標方案 ID，例如 "basic", "pro" */
    @NotBlank(message = "planId 不可為空")
    private String planId;
}
