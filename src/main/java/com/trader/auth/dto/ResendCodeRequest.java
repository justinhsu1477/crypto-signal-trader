package com.trader.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重新發送 OTP 驗證碼請求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResendCodeRequest {

    @NotBlank(message = "Email 不可為空")
    @Email(message = "Email 格式不正確")
    private String email;
}
