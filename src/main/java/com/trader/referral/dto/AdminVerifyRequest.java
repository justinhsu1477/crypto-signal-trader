package com.trader.referral.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理員驗證推薦碼請求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminVerifyRequest {

    @NotBlank(message = "userId 不可為空")
    private String userId;

    private boolean approved;

    private String notes;
}
