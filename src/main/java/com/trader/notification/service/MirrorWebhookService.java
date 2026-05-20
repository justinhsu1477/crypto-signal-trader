package com.trader.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.MirrorConfig;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.entity.SignalSourceConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把監聽到的 Discord 訊息轉發到 admin 自己的 Discord channel webhook。
 *
 * <p>三層 kill switch：
 * <ol>
 *     <li>全域 {@link MirrorConfig#isEnabled()} — 出事時 yml/env 改 false 一鍵全停</li>
 *     <li>per-source {@link SignalSourceConfig#isMirrorEnabled()} — 單一源關掉</li>
 *     <li>URL 是否設定（null/blank 視為未設定）</li>
 * </ol>
 *
 * <p>失敗永遠 swallow：mirror 是觀測層，AES decrypt fail / HTTP 4xx-5xx / IOException
 * 都不該影響 audit 寫入跟 broadcast-trade 主流程。
 *
 * <p>Phase 1 圖片走 Discord CDN URL embed — 24 小時後 URL 過期會破圖。
 * Phase 2 再做永久 multipart upload。
 */
@Slf4j
@Service
public class MirrorWebhookService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AesEncryptionUtil aes;
    private final MirrorConfig config;

    public MirrorWebhookService(OkHttpClient httpClient,
                                ObjectMapper objectMapper,
                                AesEncryptionUtil aes,
                                MirrorConfig config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aes = aes;
        this.config = config;
    }

    /**
     * Async 觸發 mirror — 永遠 swallow 失敗。
     *
     * @param source        對應源
     * @param msg           已寫入 audit 表的訊息
     * @param attachmentUrl 第一張圖的 Discord CDN URL（null = 純文字訊息）
     */
    @Async("auditExecutor")
    public void mirrorAsync(SignalSourceConfig source,
                            DiscordRawMessage msg,
                            String attachmentUrl) {
        try {
            // L1: global kill switch
            if (!config.isEnabled()) {
                return;
            }
            // L2: per-source flag
            if (!source.isMirrorEnabled()) {
                return;
            }
            // L3: URL presence
            String encrypted = source.getMirrorWebhookUrl();
            if (encrypted == null || encrypted.isBlank()) {
                return;
            }

            String webhookUrl;
            try {
                webhookUrl = aes.decrypt(encrypted);
            } catch (Exception e) {
                log.warn("mirror: AES decrypt failed for source {}: {}", source.getName(), e.getMessage());
                return;
            }

            String json = objectMapper.writeValueAsString(buildPayload(source, msg, attachmentUrl));
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("mirror: webhook returned {} for source {} msg {}",
                            response.code(), source.getName(), msg.getMessageId());
                }
            }
        } catch (IOException e) {
            log.warn("mirror: post failed for source {} msg {}: {}",
                    source.getName(), msg.getMessageId(), e.getMessage());
        } catch (Exception e) {
            // catch-all — mirror failure must never propagate up to caller
            log.warn("mirror: unexpected error for source {}: {}", source.getName(), e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(SignalSourceConfig source,
                                              DiscordRawMessage msg,
                                              String attachmentUrl) {
        Map<String, Object> embed = new HashMap<>();
        embed.put("description", msg.getContent() != null ? msg.getContent() : "");

        Map<String, Object> footer = new HashMap<>();
        String displayName = source.getDisplayName() != null ? source.getDisplayName() : source.getName();
        String ts = msg.getMessageTimestamp() != null
                ? msg.getMessageTimestamp().format(TIME_FMT)
                : "";
        footer.put("text", String.format("Source: %s | %s", displayName, ts));
        embed.put("footer", footer);

        if (attachmentUrl != null && !attachmentUrl.isBlank()) {
            Map<String, Object> image = new HashMap<>();
            image.put("url", attachmentUrl);
            embed.put("image", image);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("username", displayName + " (mirror)");
        payload.put("embeds", List.of(embed));
        return payload;
    }
}
