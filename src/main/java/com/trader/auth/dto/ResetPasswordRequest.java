package com.trader.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 密碼重設請求（從忘記密碼 email 連結進入）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "Token 不可為空")
    private String token;

    @NotBlank(message = "請輸入新密碼")
    @Size(min = 8, message = "密碼至少 8 個字元")
    private String newPassword;

    @NotBlank(message = "請確認新密碼")
    private String confirmPassword;
}
