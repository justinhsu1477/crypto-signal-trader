package com.trader.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 建立/更新公告請求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAnnouncementRequest {

    @NotBlank(message = "標題不能為空")
    @Size(max = 200, message = "標題最多 200 字")
    private String title;

    @NotBlank(message = "內容不能為空")
    private String content;

    /** GENERAL, MAINTENANCE, UPDATE, URGENT, PROMOTION（預設 GENERAL） */
    @Builder.Default
    private String category = "GENERAL";

    /** LOW, NORMAL, HIGH, CRITICAL（預設 NORMAL） */
    @Builder.Default
    private String priority = "NORMAL";

    /** ALL 或逗號分隔：DISCORD,LINE,WEBSOCKET（預設 ALL） */
    @Builder.Default
    private String channels = "ALL";

    /** 附圖 URL（可選，必須為 HTTPS） */
    @Size(max = 500, message = "圖片 URL 最多 500 字")
    private String imageUrl;
}
