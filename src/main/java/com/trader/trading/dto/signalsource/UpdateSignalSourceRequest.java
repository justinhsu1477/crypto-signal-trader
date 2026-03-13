package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSignalSourceRequest {

    private String name;
    private String displayName;
    private String description;
    private Boolean enabled;

    /** 路由模式：GLOBAL（全員廣播）或 ASSIGNED（僅綁定用戶） */
    private String routingMode;

    /** 交易模式：AUTO / SHADOW / MANUAL */
    private String tradeMode;

    /** 風險倍率（0.1 ~ 3.0） */
    private Double riskMultiplier;
}
