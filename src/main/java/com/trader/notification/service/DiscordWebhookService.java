package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.WebhookConfig;
import com.trader.user.repository.UserDiscordWebhookRepository;
import com.trader.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * - 本地快取 webhook URL + 通知開關（TTL 5 分鐘 + 手動 evict）
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
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分鐘

    private final OkHttpClient httpClient;
    private final WebhookConfig webhookConfig;
    private final UserDiscordWebhookRepository userWebhookRepository;
    private final UserRepository userRepository;

    // 本地快取：webhook URL + 通知開關（很少變動，避免每次通知都查 DB）
    private final ConcurrentHashMap<String, CacheEntry<Optional<String>>> webhookUrlCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<Boolean>> notificationEnabledCache = new ConcurrentHashMap<>();

    public DiscordWebhookService(OkHttpClient httpClient, WebhookConfig webhookConfig,
                                  UserDiscordWebhookRepository userWebhookRepository,
                                  UserRepository userRepository) {
        this.httpClient = httpClient;
        this.webhookConfig = webhookConfig;
        this.userWebhookRepository = userWebhookRepository;
        this.userRepository = userRepository;
    }

    /** 快取條目：值 + 過期時間 */
    private record CacheEntry<T>(T value, long expireAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
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
     * 取得用戶的 webhook URL（帶快取）
     * 優先順序：
     * 1. 用戶自定義的 webhook（如果 per-user enabled + 有啟用的 webhook）
     * 2. 全局 webhook（如果啟用 fallback + 有全局 URL）
     * 3. null（都沒有）
     */
    public Optional<String> getUserWebhookUrl(String userId) {
        // 查快取
        CacheEntry<Optional<String>> cached = webhookUrlCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        // Cache miss 或過期 → 查 DB
        Optional<String> result = resolveWebhookUrlFromDb(userId);
        webhookUrlCache.put(userId, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MS));
        return result;
    }

    /** 從 DB 解析用戶 webhook URL（無快取） */
    private Optional<String> resolveWebhookUrlFromDb(String userId) {
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
     * 檢查 discordNotificationEnabled 主開關，若關閉則靜默跳過。
     *
     * @param userId  用戶 ID
     * @param title   標題
     * @param message 內容
     * @param color   顏色
     */
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        if (!isNotificationEnabledForUser(userId)) {
            log.debug("用戶 {} Discord 通知已關閉，跳過: {}", userId, title);
            return;
        }

        Optional<String> webhookUrl = getUserWebhookUrl(userId);
        if (webhookUrl.isPresent()) {
            sendNotificationToUrl(webhookUrl.get(), title, message, color);
        }
    }

    /**
     * 發送通知到用戶（帶分類，供未來 per-category 篩選用）
     * 目前行為與無分類版相同 — 只檢查主開關。
     *
     * @param userId   用戶 ID
     * @param category 通知分類（目前未用於篩選）
     * @param title    標題
     * @param message  內容
     * @param color    顏色
     */
    public void sendNotificationToUser(String userId, NotificationCategory category,
                                        String title, String message, int color) {
        sendNotificationToUser(userId, title, message, color);
    }

    /**
     * 檢查用戶是否啟用 Discord 通知（帶快取）
     * 用戶不存在時預設允許（保守策略，寧可多發不漏發）
     */
    private boolean isNotificationEnabledForUser(String userId) {
        CacheEntry<Boolean> cached = notificationEnabledCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        boolean enabled = userRepository.findById(userId)
                .map(user -> user.isDiscordNotificationEnabled())
                .orElse(true);
        notificationEnabledCache.put(userId, new CacheEntry<>(enabled, System.currentTimeMillis() + CACHE_TTL_MS));
        return enabled;
    }

    // ==================== 快取管理 ====================

    /**
     * 清除指定用戶的所有快取（webhook URL + 通知開關）
     * 在用戶修改 webhook 或通知設定時呼叫，確保即時生效。
     */
    public void evictUserCache(String userId) {
        webhookUrlCache.remove(userId);
        notificationEnabledCache.remove(userId);
        log.debug("已清除用戶 {} 的 Discord 通知快取", userId);
    }

    /**
     * 清除所有用戶的快取（管理用途）
     */
    public void evictAllCache() {
        webhookUrlCache.clear();
        notificationEnabledCache.clear();
        log.info("已清除所有 Discord 通知快取");
    }
}
