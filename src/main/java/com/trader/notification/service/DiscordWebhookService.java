package com.trader.notification.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.config.WebhookConfig;
import com.trader.user.repository.UserDiscordWebhookRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

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

    private final OkHttpClient httpClient;
    private final WebhookConfig webhookConfig;
    private final UserDiscordWebhookRepository userWebhookRepository;

    public DiscordWebhookService(OkHttpClient httpClient, WebhookConfig webhookConfig,
                                  UserDiscordWebhookRepository userWebhookRepository) {
        this.httpClient = httpClient;
        this.webhookConfig = webhookConfig;
        this.userWebhookRepository = userWebhookRepository;
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

        sendNotificationToUrl(url, title, message, color);
    }

    /**
     * 發送通知到指定的 Webhook URL (per-user)
     *
     * @param webhookUrl 用戶自定義的 Webhook URL（可為 null，則忽略）
     * @param title      標題
     * @param message    內容
     * @param color      顏色
     */
    public void sendNotificationToUrl(String webhookUrl, String title, String message, int color) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        String timestamp = ZonedDateTime.now(AppConstants.ZONE_ID).format(TIME_FMT);
        String json = buildEmbedJson(title, message, color, timestamp);

        Request request = new Request.Builder()
                .url(webhookUrl)
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

    /**
     * 取得用戶的 webhook URL
     * 優先順序：
     * 1. 用戶自定義的 webhook（如果 per-user enabled + 有啟用的 webhook）
     * 2. 全局 webhook（如果啟用 fallback + 有全局 URL）
     * 3. null（都沒有）
     */
    public Optional<String> getUserWebhookUrl(String userId) {
        // 如果啟用了 per-user 配置
        if (webhookConfig.getPerUser().isEnabled()) {
            Optional<String> userWebhook = userWebhookRepository
                    .findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(userId)
                    .map(w -> w.getWebhookUrl());

            if (userWebhook.isPresent()) {
                return userWebhook;
            }

            // Fallback 到全局（如果配置允許）
            if (webhookConfig.getPerUser().isFallbackToGlobal()) {
                return getGlobalWebhookUrl();
            }
        } else {
            // 未啟用 per-user，直接用全局
            return getGlobalWebhookUrl();
        }

        return Optional.empty();
    }

    /**
     * 取得全局 webhook URL
     */
    private Optional<String> getGlobalWebhookUrl() {
        if (webhookConfig.isEnabled()) {
            String globalUrl = webhookConfig.getUrl();
            if (globalUrl != null && !globalUrl.isBlank()) {
                return Optional.of(globalUrl);
            }
        }
        return Optional.empty();
    }

    /**
     * 發送通知到用戶（優先用用戶自定義 webhook）
     *
     * @param userId  用戶 ID
     * @param title   標題
     * @param message 內容
     * @param color   顏色
     */
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        Optional<String> webhookUrl = getUserWebhookUrl(userId);
        if (webhookUrl.isPresent()) {
            sendNotificationToUrl(webhookUrl.get(), title, message, color);
        }
    }
}
