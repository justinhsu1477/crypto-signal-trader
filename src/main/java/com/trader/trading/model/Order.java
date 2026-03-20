package com.trader.trading.model;

import com.trader.shared.model.TradeSignal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Order {
    private String symbol;
    private TradeSignal.Side side;
    private OrderType type;
    private Double price;
    private Double quantity;
    private Integer layer;

    public enum OrderType {
        ENTRY,
        CLOSE,
        MOVE_SL,
        TAKE_PROFIT
    }
}
