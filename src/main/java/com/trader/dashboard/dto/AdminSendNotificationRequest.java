package com.trader.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Admin 發送通知給特定用戶的請求 DTO
 */
@Data
public class AdminSendNotificationRequest {

    @NotEmpty(message = "至少需要一個用戶")
    private List<String> userIds;

    @NotBlank(message = "標題不能為空")
    @Size(max = 100, message = "標題最多 100 字")
    private String title;

    @NotBlank(message = "內容不能為空")
    @Size(max = 2000, message = "內容最多 2000 字")
    private String message;

    /** 顏色：GREEN / BLUE / YELLOW / RED，預設 BLUE */
    private String color;
}
