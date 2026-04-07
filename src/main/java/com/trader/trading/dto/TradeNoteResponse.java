package com.trader.trading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeNoteResponse {
    private Long id;
    private String tradeId;
    private String note;
    private String tags;
    private Integer rating;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
