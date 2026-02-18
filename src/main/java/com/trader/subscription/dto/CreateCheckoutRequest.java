package com.trader.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCheckoutRequest {

    /** 方案 ID，例如 "basic", "pro" */
    @NotBlank(message = "planId 不可為空")
    private String planId;
}
