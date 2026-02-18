package com.trader.notification.service;

import com.trader.shared.config.WebhookConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Discord Webhook 通知服務
 *
 * 負責將所有交易操作結果（成功/失敗/攔截/跳過）
 * 即時推送到使用者自己的 Discord 文字頻道。
 *
 * 特性：
 * - 非同步發送（enqueue），不阻塞交易流程
 * - enabled=false 或 URL 為空時靜默跳過
 * - 使用 Discord Embed 格式（帶顏色條和時間戳記）
 */
@Slf4j
@Service
public class DiscordWebhookService {

    // 顏色常量
    public static final int COLOR_GREEN  = 0x00FF00;  // 成功
    public static final int COLOR_RED    = 0xFF0000;  // 失敗
    public static final int COLOR_YELLOW = 0xFFFF00;  // 警告/跳過
    public static final int COLOR_BLUE   = 0x3498DB;  // 資訊

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    private final OkHttpClient httpClient;
    private final WebhookConfig webhookConfig;

    public DiscordWebhookService(OkHttpClient httpClient, WebhookConfig webhookConfig) {
        this.httpClient = httpClient;
        this.webhookConfig = webhookConfig;
    }

    /**
     * 發送通知到 Discord
     *
     * @param title   標題（例如 "✅ ENTRY 成功"）
     * @param message 內容（多行描述）
     * @param color   嵌入顏色（用上面的常量）
     */
    public void sendNotification(String title, String message, int color) {
        if (!webhookConfig.isEnabled()) {
            return;
        }

        String url = webhookConfig.getUrl();
        if (url == null || url.isBlank()) {
            return;
        }

        String timestamp = ZonedDateTime.now(TAIPEI_ZONE).format(TIME_FMT);

        // 建構 Discord Embed JSON
        String json = buildEmbedJson(title, message, color, timestamp);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        // 非同步發送，不阻塞主流程
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("Discord Webhook 發送失敗: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.warn("Discord Webhook 回應異常: HTTP {} - {}",
                                response.code(),
                                response.body() != null ? response.body().string() : "no body");
                    } else {
                        log.debug("Discord Webhook 發送成功");
                    }
                } catch (IOException e) {
                    log.warn("讀取 Webhook 回應失敗: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 建構 Discord Embed JSON
     *
     * Discord Webhook 格式：
     * {
     *   "embeds": [{
     *     "title": "...",
     *     "description": "...",
     *     "color": 65280,
     *     "footer": { "text": "Crypto Signal Trader | 2024-01-01 12:00:00" }
     *   }]
     * }
     */
    private String buildEmbedJson(String title, String description, int color, String timestamp) {
        // 手動建 JSON，避免額外引入 JSON library（OkHttp 不需要）
        // 轉義特殊字元
        String safeTitle = escapeJson(title);
        String safeDesc = escapeJson(description);

        return String.format("""
                {
                  "embeds": [{
                    "title": "%s",
                    "description": "%s",
                    "color": %d,
                    "footer": {
                      "text": "Crypto Signal Trader | %s"
                    }
                  }]
                }""", safeTitle, safeDesc, color, timestamp);
    }

    /**
     * 轉義 JSON 特殊字元
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
