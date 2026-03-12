package com.trader.trading.dto.signalsource;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSignalSourceRequest {

    /** Admin 內部名稱（如「陳哥VIP群」） */
    @NotBlank(message = "名稱不可為空")
    private String name;

    /** 用戶看到的別名（如「訊號源 A」） */
    @NotBlank(message = "顯示名稱不可為空")
    private String displayName;

    /** Discord channel ID */
    private String channelId;

    /** Discord guild ID */
    private String guildId;

    /** Admin 備註 */
    private String description;

    /** 路由模式：GLOBAL（全員廣播）或 ASSIGNED（僅綁定用戶），預設 ASSIGNED */
    private String routingMode;
}
