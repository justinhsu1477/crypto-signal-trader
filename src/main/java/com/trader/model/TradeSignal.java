package com.trader.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 解析後的交易訊號
 * 對應陳哥的訊號格式：
 *   幣種: BTC
 *   方向: SHORT / LONG
 *   入場價: 70800-72000
 *   止損: 72800
 *   止盈: [68400, 66700]
 */
@Data
@Builder
public class TradeSignal {

    private String symbol;           // e.g. "BTCUSDT"
    private Side side;               // LONG or SHORT
    private double entryPriceLow;    // 入場價下限
    private double entryPriceHigh;   // 入場價上限
    private double stopLoss;         // 止損
    private List<Double> takeProfits; // 止盈 (可多個目標)
    private Integer leverage;        // 槓桿 (可選, 訊號沒給就用預設)
    private String rawMessage;       // 原始訊息
    @Builder.Default
    private SignalType signalType = SignalType.ENTRY;  // 訊號類型

    public enum Side {
        LONG, SHORT
    }

    public enum SignalType {
        ENTRY,      // 📢 開單訊號
        CANCEL,     // ⚠️ 取消掛單
        INFO        // 🚀🛑💰 資訊通知
    }
}
