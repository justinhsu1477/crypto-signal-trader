package com.trader.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 設定密碼請求（OAuth 用戶首次設定密碼）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetPasswordRequest {

    @NotBlank(message = "請輸入新密碼")
    @Size(min = 8, message = "密碼至少 8 個字元")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z]).+$", message = "密碼需同時包含大小寫字母")
    private String newPassword;

    @NotBlank(message = "請確認新密碼")
    private String confirmPassword;
}
