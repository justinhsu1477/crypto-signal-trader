package com.trader.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 結構化交易請求 DTO
 * 接收 Python AI 解析後的 JSON
 */
@Data
public class TradeRequest {

    private String action;      // ENTRY, CLOSE, MOVE_SL, CANCEL

    private String symbol;      // BTCUSDT

    private String side;        // LONG, SHORT (ENTRY 用)

    @JsonProperty("entry_price")
    private Double entryPrice;

    @JsonProperty("stop_loss")
    private Double stopLoss;

    @JsonProperty("take_profit")
    private Double takeProfit;

    @JsonProperty("close_ratio")
    private Double closeRatio;  // CLOSE 用 (0.5=平一半, null=全平)

    @JsonProperty("new_stop_loss")
    private Double newStopLoss; // MOVE_SL 用

    private SignalSource source; // 訊號來源 (可選)
}
