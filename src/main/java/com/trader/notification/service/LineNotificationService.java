package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.LineConfig;
import com.trader.user.entity.User;
import com.trader.user.repository.UserLineBindingRepository;
import com.trader.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LINE Push API 通知服務
 *
 * 透過 LINE Messaging API 推播通知到用戶的 LINE 帳號。
 *
 * 特性：
 * - 非同步發送（enqueue），不阻塞交易流程
 * - enabled=false 或無綁定時靜默跳過
 * - 純文字訊息格式（LINE 不支援 Discord Embed）
 * - 本地快取 line_user_id + 通知開關（TTL 5 分鐘 + 手動 evict）
 */
@Slf4j
@Service
public class LineNotificationService implements NotificationService {

    private static final String PUSH_API_URL = "https://api.line.me/v2/bot/message/push";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分鐘
    /** LINE 文字訊息上限 5000 字元，預留空間給 JSON 結構與轉義 */
    private static final int LINE_TEXT_MAX_LENGTH = 4800;

    private final OkHttpClient httpClient;
    private final LineConfig lineConfig;
    private final UserLineBindingRepository lineBindingRepository;
    private final UserRepository userRepository;

    // 快取：line_user_id + 通知開關 + admin IDs
    private final ConcurrentHashMap<String, CacheEntry<Optional<String>>> lineUserIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<Boolean>> notificationEnabledCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<String>>> adminIdsCache = new ConcurrentHashMap<>();
    private static final String ADMIN_CACHE_KEY = "admin_user_ids";

    public LineNotificationService(OkHttpClient httpClient, LineConfig lineConfig,
                                   UserLineBindingRepository lineBindingRepository,
                                   UserRepository userRepository) {
        this.httpClient = httpClient;
        this.lineConfig = lineConfig;
        this.lineBindingRepository = lineBindingRepository;
        this.userRepository = userRepository;
    }

    /** 快取條目：值 + 過期時間 */
    private record CacheEntry<T>(T value, long expireAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    // ==================== 通知方法 ====================

    /**
     * 發送全局通知
     * LINE 無全局 webhook 概念，改為發送到所有 Admin。
     */
    @Override
    public void sendNotification(String title, String message, int color) {
        if (!lineConfig.isEnabled()) return;
        sendNotificationToAdmins(title, message, color);
    }

    /**
     * 發送通知到指定用戶的 LINE 帳號
     * 檢查 lineNotificationEnabled 主開關 + 是否有 LINE 綁定。
     */
    @Override
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        if (!lineConfig.isEnabled()) return;
        if (!isNotificationEnabledForUser(userId)) {
            log.debug("用戶 {} LINE 通知已關閉，跳過: {}", userId, title);
            return;
        }

        Optional<String> lineUserId = getLineUserIdForUser(userId);
        lineUserId.ifPresent(lid -> pushMessage(lid, title, message));
    }

    /**
     * 發送通知到指定用戶（帶分類）
     * 目前行為與無分類版相同 — 只檢查主開關。
     */
    @Override
    public void sendNotificationToUser(String userId, NotificationCategory category,
                                       String title, String message, int color) {
        sendNotificationToUser(userId, title, message, color);
    }

    /**
     * 發送公告通知到用戶（支援附圖）
     * 用於 AnnouncementConsumer，有圖時送 text + image 兩則訊息。
     */
    public void sendAnnouncementToUser(String userId, String title, String message, int color, String imageUrl) {
        if (!lineConfig.isEnabled()) return;
        if (!isNotificationEnabledForUser(userId)) {
            log.debug("用戶 {} LINE 通知已關閉，跳過公告: {}", userId, title);
            return;
        }

        Optional<String> lineUserId = getLineUserIdForUser(userId);
        lineUserId.ifPresent(lid -> pushAnnouncementMessage(lid, title, message, imageUrl));
    }

    /**
     * 發送通知到所有 ADMIN 的 LINE 帳號
     */
    @Override
    public void sendNotificationToAdmins(String title, String message, int color) {
        if (!lineConfig.isEnabled()) return;
        for (String adminId : getAdminUserIds()) {
            sendNotificationToUser(adminId, title, message, color);
        }
    }

    /**
     * 發送通知到所有 ADMIN（帶用戶 displayName 前綴）
     */
    @Override
    public void sendNotificationToAdmins(String displayName, String title, String message, int color) {
        String prefixed = (displayName != null && !displayName.isBlank())
                ? "用戶: " + displayName + "\n" + message
                : message;
        sendNotificationToAdmins(title, prefixed, color);
    }

    // ==================== 快取管理 ====================

    @Override
    public void evictUserCache(String userId) {
        lineUserIdCache.remove(userId);
        notificationEnabledCache.remove(userId);
        log.debug("已清除用戶 {} 的 LINE 通知快取", userId);
    }

    @Override
    public void evictAllCache() {
        lineUserIdCache.clear();
        notificationEnabledCache.clear();
        adminIdsCache.clear();
        log.info("已清除所有 LINE 通知快取");
    }

    // ==================== Private Helpers ====================

    /**
     * 推送訊息到 LINE 用戶
     *
     * LINE 文字訊息上限 5000 字元，超過會被 API 拒絕（HTTP 400）。
     * 此方法在發送前檢查長度，超過時截斷並加上提示。
     */
    private void pushMessage(String lineUserId, String title, String message) {
        String timestamp = ZonedDateTime.now(AppConstants.ZONE_ID).format(TIME_FMT);
        String footer = "Crypto Signal Trader | " + timestamp;

        // 組合完整文字，計算實際長度（未 JSON 轉義前）
        String fullText = title + "\n\n" + message + "\n\n" + footer;

        // 超過 LINE 上限時截斷 message 部分
        if (fullText.length() > LINE_TEXT_MAX_LENGTH) {
            String suffix = "\n\n...（訊息過長已截斷）";
            int overhead = title.length() + "\n\n".length() + suffix.length()
                    + "\n\n".length() + footer.length();
            int allowedMessageLen = LINE_TEXT_MAX_LENGTH - overhead;
            if (allowedMessageLen > 0) {
                message = message.substring(0, Math.min(message.length(), allowedMessageLen)) + suffix;
            } else {
                message = "（訊息過長無法顯示）" + suffix;
            }
            log.info("LINE 訊息已截斷: 原始 {} 字元 → {}", fullText.length(), LINE_TEXT_MAX_LENGTH);
        }

        String body = buildPushJson(lineUserId, title, message, timestamp);

        Request request = new Request.Builder()
                .url(PUSH_API_URL)
                .addHeader("Authorization", "Bearer " + lineConfig.getChannelAccessToken())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("LINE Push 發送失敗: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.warn("LINE Push 回應異常: HTTP {} - {}",
                                response.code(),
                                response.body() != null ? response.body().string() : "no body");
                    } else {
                        log.debug("LINE Push 發送成功");
                    }
                } catch (IOException e) {
                    log.warn("讀取 LINE 回應失敗: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 推送公告訊息到 LINE（支援附圖）
     * 有 imageUrl 時送 text + image 兩則訊息（LINE 單次 push 最多 5 則）。
     */
    private void pushAnnouncementMessage(String lineUserId, String title, String message, String imageUrl) {
        String timestamp = ZonedDateTime.now(AppConstants.ZONE_ID).format(TIME_FMT);
        String footer = "Crypto Signal Trader | " + timestamp;
        String fullText = title + "\n\n" + message + "\n\n" + footer;

        // 截斷處理
        if (fullText.length() > LINE_TEXT_MAX_LENGTH) {
            String suffix = "\n\n...（訊息過長已截斷）";
            int overhead = title.length() + "\n\n".length() + suffix.length()
                    + "\n\n".length() + footer.length();
            int allowedLen = LINE_TEXT_MAX_LENGTH - overhead;
            if (allowedLen > 0) {
                message = message.substring(0, Math.min(message.length(), allowedLen)) + suffix;
            }
        }

        String body;
        if (imageUrl != null && !imageUrl.isBlank()) {
            body = buildPushJsonWithImage(lineUserId, title, message, timestamp, imageUrl);
        } else {
            body = buildPushJson(lineUserId, title, message, timestamp);
        }

        Request request = new Request.Builder()
                .url(PUSH_API_URL)
                .addHeader("Authorization", "Bearer " + lineConfig.getChannelAccessToken())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("LINE 公告推送失敗: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.warn("LINE 公告推送回應異常: HTTP {} - {}",
                                response.code(),
                                response.body() != null ? response.body().string() : "no body");
                    } else {
                        log.debug("LINE 公告推送成功（含圖片={}）", imageUrl != null);
                    }
                } catch (IOException e) {
                    log.warn("讀取 LINE 回應失敗: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 建構 LINE Push API JSON（含圖片）
     * text + image 兩則訊息：LINE 單次 push 最多 5 則
     */
    private String buildPushJsonWithImage(String to, String title, String message, String timestamp, String imageUrl) {
        String fullText = escapeJson(title) + "\\n\\n"
                + escapeJson(message) + "\\n\\n"
                + escapeJson("Crypto Signal Trader | " + timestamp);
        String safeImageUrl = escapeJson(imageUrl);
        return String.format("""
                {
                  "to": "%s",
                  "messages": [{
                    "type": "text",
                    "text": "%s"
                  }, {
                    "type": "image",
                    "originalContentUrl": "%s",
                    "previewImageUrl": "%s"
                  }]
                }""", to, fullText, safeImageUrl, safeImageUrl);
    }

    /**
     * 建構 LINE Push API JSON
     *
     * LINE Push API 格式：
     * {
     *   "to": "lineUserId",
     *   "messages": [{"type": "text", "text": "..."}]
     * }
     */
    private String buildPushJson(String to, String title, String message, String timestamp) {
        String fullText = escapeJson(title) + "\\n\\n"
                + escapeJson(message) + "\\n\\n"
                + escapeJson("Crypto Signal Trader | " + timestamp);
        return String.format("""
                {
                  "to": "%s",
                  "messages": [{
                    "type": "text",
                    "text": "%s"
                  }]
                }""", to, fullText);
    }

    /** 轉義 JSON 特殊字元 */
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
     * 取得用戶的 LINE user ID（帶快取）
     * 查詢 user_line_bindings 表，只返回 enabled=true 的綁定。
     */
    private Optional<String> getLineUserIdForUser(String userId) {
        CacheEntry<Optional<String>> cached = lineUserIdCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        Optional<String> result = lineBindingRepository.findByUserIdAndEnabledTrue(userId)
                .map(binding -> binding.getLineUserId());
        lineUserIdCache.put(userId, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MS));
        return result;
    }

    /**
     * 檢查用戶是否啟用 LINE 通知（帶快取）
     * 用戶不存在時預設允許（保守策略，寧可多發不漏發）
     */
    private boolean isNotificationEnabledForUser(String userId) {
        CacheEntry<Boolean> cached = notificationEnabledCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        boolean enabled = userRepository.findById(userId)
                .map(User::isLineNotificationEnabled)
                .orElse(true);
        notificationEnabledCache.put(userId, new CacheEntry<>(enabled, System.currentTimeMillis() + CACHE_TTL_MS));
        return enabled;
    }

    /**
     * 取得所有已啟用 ADMIN 用戶 ID（帶快取，5 分鐘 TTL）
     */
    private List<String> getAdminUserIds() {
        CacheEntry<List<String>> cached = adminIdsCache.get(ADMIN_CACHE_KEY);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        List<String> adminIds = userRepository.findByRole(User.Role.ADMIN).stream()
                .filter(User::isEnabled)
                .map(User::getUserId)
                .toList();
        adminIdsCache.put(ADMIN_CACHE_KEY,
                new CacheEntry<>(adminIds, System.currentTimeMillis() + CACHE_TTL_MS));
        return adminIds;
    }
}
