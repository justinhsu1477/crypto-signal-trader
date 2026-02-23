package com.trader.referral.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交交易所 UID 請求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitUidRequest {

    @NotBlank(message = "交易所 UID 不可為空")
    @Size(max = 64, message = "交易所 UID 長度不可超過 64")
    @Pattern(regexp = "^\\s*\\d{6,20}\\s*$", message = "交易所 UID 格式錯誤，請輸入 6~20 位數字")
    private String exchangeUid;
}
