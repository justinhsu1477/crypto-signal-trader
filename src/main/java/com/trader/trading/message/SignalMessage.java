package com.trader.trading.message;

import com.trader.shared.model.TradeSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RabbitMQ 消息：廣播信號
 * 從 Discord 監聽得到的信號，發送到 signal-queue
 * SignalConsumer 會為每個用戶生成 ExecutionTask
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 交易信號物件 */
    private TradeSignal signal;

    /** 信號源標籤（用於 logging 和 Discord 通知） */
    private String source;  // e.g., "discord-channel-123"

    /** 信號接收時間 */
    private LocalDateTime receivedAt;

    /** 訊息追蹤 ID（用於去重和日誌追蹤） */
    private String messageId;

    /** 廣播的用戶數（完成後填充） */
    private int broadcastedUserCount;

    /** 成功用戶數（完成後填充） */
    private int successCount;

    /** 失敗用戶數（完成後填充） */
    private int failedCount;
}
