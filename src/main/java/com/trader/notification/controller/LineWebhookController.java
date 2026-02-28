package com.trader.notification.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.notification.service.LineLinkingService;
import com.trader.shared.config.LineConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * LINE Webhook Controller
 *
 * 接收 LINE Platform 推送的事件：
 * - follow：用戶加入好友 → 回覆歡迎訊息
 * - unfollow：用戶封鎖 → 停用綁定
 * - message(text)：用戶發送連結碼 → 綁定用戶
 *
 * 安全：HMAC-SHA256 簽名驗證（Channel Secret）
 * 路徑：POST /api/line/webhook（公開端點，AuthConfig permitAll）
 */
@Slf4j
@RestController
@RequestMapping("/api/line")
@RequiredArgsConstructor
public class LineWebhookController {

    private final LineConfig lineConfig;
    private final LineLinkingService lineLinkingService;
    private final Gson gson = new Gson();

    /**
     * LINE Webhook 接收端點
     *
     * LINE 會在以下時機發送 POST：
     * 1. Webhook URL 驗證（events = []）
     * 2. 用戶加入好友（follow event）
     * 3. 用戶封鎖（unfollow event）
     * 4. 用戶發送訊息（message event）
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-Line-Signature") String signature,
            @RequestBody String body) {

        // 1. HMAC-SHA256 簽名驗證
        if (!verifySignature(body, signature)) {
            log.warn("LINE Webhook 簽名驗證失敗");
            return ResponseEntity.badRequest().build();
        }

        // 2. 解析事件
        JsonObject root = gson.fromJson(body, JsonObject.class);
        JsonArray events = root.getAsJsonArray("events");

        // 空事件 = LINE Webhook URL 驗證 ping
        if (events == null || events.isEmpty()) {
            log.debug("LINE Webhook 驗證 ping");
            return ResponseEntity.ok().build();
        }

        // 3. 處理每個事件
        for (JsonElement elem : events) {
            try {
                processEvent(elem.getAsJsonObject());
            } catch (Exception e) {
                log.error("LINE Webhook 事件處理失敗: {}", e.getMessage(), e);
            }
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 處理單一事件
     */
    private void processEvent(JsonObject event) {
        String type = event.get("type").getAsString();
        JsonObject source = event.getAsJsonObject("source");
        String lineUserId = source.get("userId").getAsString();
        String replyToken = event.has("replyToken") ? event.get("replyToken").getAsString() : null;

        switch (type) {
            case "follow" -> lineLinkingService.handleFollow(lineUserId, replyToken);
            case "unfollow" -> lineLinkingService.handleUnfollow(lineUserId);
            case "message" -> {
                JsonObject message = event.getAsJsonObject("message");
                if ("text".equals(message.get("type").getAsString())) {
                    String text = message.get("text").getAsString().trim();
                    lineLinkingService.handleMessage(lineUserId, text, replyToken);
                }
            }
            default -> log.debug("LINE Webhook 忽略事件類型: {}", type);
        }
    }

    /**
     * HMAC-SHA256 簽名驗證
     *
     * 規格：https://developers.line.biz/en/docs/messaging-api/receiving-messages/#verifying-signatures
     * 使用 constant-time comparison 防止 timing attack。
     */
    private boolean verifySignature(String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    lineConfig.getChannelSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(hash);
            // constant-time comparison 防止 timing attack
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("LINE 簽名計算失敗: {}", e.getMessage());
            return false;
        }
    }
}
