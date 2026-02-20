package com.trader.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettingsDefaultsResponse {

    private String planId;
    private Double maxRiskPercent;
    private Integer maxPositions;
    private Integer maxSymbols;
    private Integer dcaLayersAllowed;
}
