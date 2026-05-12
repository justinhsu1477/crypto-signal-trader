package com.trader.shared.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 結構化交易請求 DTO
 * 接收 Python AI 解析後的 JSON
 *
 * Python 端可能多帶尚未在 Java 端落地的欄位（如 prompt_version 等 audit 用元資料），
 * 用 @JsonIgnoreProperties 容忍 unknown，與 Spring Boot 預設行為一致。
 * 這也讓單元測試用 plain ObjectMapper 反序列化時能跟 production 行為一致。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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
    private Double newStopLoss; // MOVE_SL / CLOSE 部分平倉用

    @JsonProperty("new_take_profit")
    private Double newTakeProfit; // MOVE_SL / CLOSE 部分平倉用

    @JsonProperty("is_dca")
    private Boolean isDca;       // 是否為補倉訊號（DCA）

    private SignalSource source; // 訊號來源 (可選)

    @JsonProperty("signal_timestamp")
    private Long signalTimestamp;  // 訊號產生時間（epoch millis），用於時效性驗證（可選）

    @JsonProperty("target_user_ids")
    private List<String> targetUserIds;  // 可選，null/空 = 全部用戶（Admin 緊急廣播指定用戶用）

    @JsonProperty("position_size_modifier")
    private Double positionSizeModifier;  // 倉位修飾（0.5=半倉/輕倉, null=預設100%）
}
