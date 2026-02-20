package com.trader.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettingsResponse {

    private String userId;
    private Double riskPercent;
    private Integer maxLeverage;
    private Integer maxDcaLayers;
    private Double maxPositionSizeUsdt;
    private List<String> allowedSymbols;
    private boolean autoSlEnabled;
    private boolean autoTpEnabled;
    private String updatedAt;
}
