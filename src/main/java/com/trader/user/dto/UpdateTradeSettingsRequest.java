package com.trader.user.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateTradeSettingsRequest {

    private Double riskPercent;
    private Integer maxLeverage;
    private Integer maxDcaLayers;
    private Double maxPositionSizeUsdt;
    private List<String> allowedSymbols;
    private Double dailyLossLimitUsdt;
    private Double dcaRiskMultiplier;
    private Boolean autoSlEnabled;
    private Boolean autoTpEnabled;
}
