package com.trader.trading.message;

import com.trader.shared.model.TradeSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RabbitMQ 消息：執行任務（針對單個用戶）
 * SignalConsumer 生成此消息並發送到 execution-queue
 * ExecutionWorker 消費此消息並執行交易
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionTask implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 目標用戶 ID */
    private String userId;

    /** 交易信號 */
    private TradeSignal signal;

    /** 該用戶的 API Key ID（預先查詢好，避免重複查詢） */
    private String apiKeyId;

    /** 信號源標籤（用於 logging） */
    private String source;

    /** 訊息追蹤 ID（與 SignalMessage 相同，用於追蹤） */
    private String messageId;

    /** 執行序列（e.g., user_1_of_10，用於 logging） */
    private String executionSequence;

    /** 任務建立時間（毫秒） */
    private long createdTimeMs;

    /** 重試次數 */
    @Builder.Default
    private int retryCount = 0;

    /** 最大重試次數 */
    @Builder.Default
    private int maxRetries = 2;
}
