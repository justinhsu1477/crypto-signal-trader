package com.trader.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告推播 MQ 訊息 DTO
 *
 * 透過 RabbitMQ Fanout Exchange 傳遞，
 * 由 AnnouncementConsumer 的 Discord / LINE listener 各自消費。
 *
 * 使用 Jackson2JsonMessageConverter 序列化為 JSON。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementMessage implements Serializable {

    private Long announcementId;
    private String title;
    private String content;
    private String category;    // GENERAL, MAINTENANCE, UPDATE, URGENT, PROMOTION
    private String priority;    // LOW, NORMAL, HIGH, CRITICAL
    private String channels;    // ALL 或逗號分隔: DISCORD,LINE,WEBSOCKET
    private String imageUrl;    // 附圖 URL（可選）
    private LocalDateTime publishedAt;
    private String createdBy;
}
