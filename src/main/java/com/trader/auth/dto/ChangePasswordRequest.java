package com.trader.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改密碼請求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "請輸入目前密碼")
    private String currentPassword;

    @NotBlank(message = "請輸入新密碼")
    @Size(min = 8, message = "密碼至少 8 個字元")
    private String newPassword;

    @NotBlank(message = "請確認新密碼")
    private String confirmPassword;
}
