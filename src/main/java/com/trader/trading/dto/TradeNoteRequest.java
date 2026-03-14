package com.trader.trading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeNoteRequest {
    private String note;
    private String tags;       // 逗號分隔: "追高,情緒交易"
    private Integer rating;    // 1-5
}
