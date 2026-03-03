package com.trader.shared.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
 *
 * 註解為文檔用途；實際驗證由 TradeSignalValidator 程式化執行。
 */
@Data
@Builder
public class TradeSignal {

    @NotBlank
    private String symbol;           // e.g. "BTCUSDT"
    private Side side;               // LONG or SHORT（DCA 可 null，從持倉推斷）
    @Positive
    private double entryPriceLow;    // 入場價下限
    @PositiveOrZero
    private double entryPriceHigh;   // 入場價上限
    @PositiveOrZero
    private double stopLoss;         // 止損
    private List<Double> takeProfits; // 止盈 (可多個目標)
    private Integer leverage;        // 槓桿 (可選, 訊號沒給就用預設)
    private String rawMessage;       // 原始訊息
    @NotNull
    @Builder.Default
    private SignalType signalType = SignalType.ENTRY;  // 訊號類型
    private Double closeRatio;       // 平倉比例 (0.5=平一半, 1.0=全平, null=全平)
    private Double newStopLoss;      // MOVE_SL / CLOSE / DCA 時的新止損價
    private Double newTakeProfit;    // MOVE_SL / CLOSE / DCA 時的新止盈價
    private boolean isDca;           // 是否為補倉（DCA）
    private SignalSource source;     // 訊號來源 (可選)

    public enum Side {
        LONG, SHORT
    }

    public enum SignalType {
        ENTRY,      // 開倉
        CLOSE,      // 平倉（全平或分批）
        MOVE_SL,    // 移動止損 / 推保本
        CANCEL,     // ⚠️ 取消掛單
        INFO        // 🚀🛑💰 資訊通知
    }
}
