package com.trader.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Email 不可為空")
    @Email(message = "Email 格式不正確")
    private String email;

    @NotBlank(message = "密碼不可為空")
    @Size(min = 8, message = "密碼至少 8 個字元")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z]).+$",
            message = "密碼需同時包含大小寫字母"
    )
    private String password;

    private String name;

    @AssertTrue(message = "必須同意服務條款與風險聲明")
    private boolean termsAccepted;
}
