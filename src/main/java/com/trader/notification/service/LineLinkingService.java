package com.trader.notification.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.config.LineConfig;
import com.trader.user.entity.LineLinkingCode;
import com.trader.user.entity.UserLineBinding;
import com.trader.user.repository.LineLinkingCodeRepository;
import com.trader.user.repository.UserLineBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * LINE 帳號綁定服務
 *
 * 負責：
 * - 產生連結碼（8 碼，10 分鐘過期）
 * - 處理 LINE Webhook 事件（follow / unfollow / message）
 * - 綁定 / 解除綁定 用戶 LINE 帳號
 *
 * 綁定流程：
 * 1. 用戶在網站點「產生連結碼」→ 後端產生 8 碼存 DB
 * 2. 用戶在 LINE 對話輸入此碼 → LINE Webhook 收到 message event
 * 3. handleMessage() 比對碼 → 建立 user_line_bindings 記錄
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineLinkingService {

    private final LineConfig lineConfig;
    private final UserLineBindingRepository lineBindingRepository;
    private final LineLinkingCodeRepository linkingCodeRepository;
    private final OkHttpClient httpClient;

    private static final String REPLY_API_URL = "https://api.line.me/v2/bot/message/reply";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final SecureRandom RANDOM = new SecureRandom();
    // 排除容易混淆的字元：O/0/I/1
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /**
     * 產生連結碼（給前端 API 呼叫）
     * - 刪除該用戶所有舊碼
     * - 產生 8 碼，設定 10 分鐘過期
     */
    @Transactional
    public String generateLinkingCode(String userId) {
        linkingCodeRepository.deleteByUserId(userId);

        String code = generateCode(8);
        LineLinkingCode entity = LineLinkingCode.builder()
                .code(code)
                .userId(userId)
                .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID)
                        .plusMinutes(lineConfig.getLinkingCodeExpiryMinutes()))
                .used(false)
                .build();
        linkingCodeRepository.save(entity);

        log.info("已產生 LINE 連結碼: userId={}", userId);
        return code;
    }

    /**
     * 處理 follow 事件：用戶加入好友 → 回覆歡迎訊息
     */
    public void handleFollow(String lineUserId, String replyToken) {
        log.info("LINE 用戶加入好友: lineUserId={}", lineUserId);
        replyText(replyToken,
                "歡迎使用 Crypto Signal Trader！\n\n" +
                "請在網站設定頁面取得連結碼，然後在此輸入連結碼完成綁定。");
    }

    /**
     * 處理 unfollow 事件：用戶封鎖/刪除好友 → 停用綁定
     */
    public void handleUnfollow(String lineUserId) {
        lineBindingRepository.findByLineUserId(lineUserId).ifPresent(binding -> {
            binding.setEnabled(false);
            lineBindingRepository.save(binding);
            log.info("LINE 用戶取消關注，已停用綁定: lineUserId={} userId={}",
                    lineUserId, binding.getUserId());
        });
    }

    /**
     * 處理 message 事件：用戶發送文字 → 嘗試比對連結碼
     */
    @Transactional
    public void handleMessage(String lineUserId, String text, String replyToken) {
        // 已綁定的用戶
        Optional<UserLineBinding> existing = lineBindingRepository.findByLineUserId(lineUserId);
        if (existing.isPresent() && existing.get().isEnabled()) {
            replyText(replyToken, "您的 LINE 已綁定帳號。\n如需解除綁定，請至網站設定頁面操作。");
            return;
        }

        // 嘗試比對連結碼（8 碼大寫）
        String code = text.toUpperCase().trim();
        if (code.length() != 8) {
            replyText(replyToken, "請輸入 8 位數連結碼。\n可在網站「通知設定」取得連結碼。");
            return;
        }

        Optional<LineLinkingCode> codeEntity = linkingCodeRepository.findByCodeAndUsedFalse(code);
        if (codeEntity.isEmpty() || codeEntity.get().isExpired()) {
            replyText(replyToken, "連結碼無效或已過期。\n請重新在網站產生連結碼。");
            return;
        }

        // 標記碼已使用
        LineLinkingCode linkCode = codeEntity.get();
        linkCode.setUsed(true);
        linkingCodeRepository.save(linkCode);

        // 建立或更新綁定
        UserLineBinding binding = lineBindingRepository.findById(linkCode.getUserId())
                .orElse(UserLineBinding.builder().userId(linkCode.getUserId()).build());
        binding.setLineUserId(lineUserId);
        binding.setEnabled(true);
        lineBindingRepository.save(binding);

        log.info("LINE 綁定成功: userId={} lineUserId={}", linkCode.getUserId(), lineUserId);
        replyText(replyToken, "✅ 綁定成功！\n您現在會收到交易通知。\n\n如需調整通知設定，請至網站設定頁面。");
    }

    /**
     * 查詢用戶 LINE 綁定狀態
     */
    public Optional<UserLineBinding> getBinding(String userId) {
        return lineBindingRepository.findById(userId);
    }

    /**
     * 解除 LINE 綁定（給前端 API 呼叫）
     */
    @Transactional
    public void unbind(String userId) {
        lineBindingRepository.deleteById(userId);
        log.info("LINE 已解除綁定: userId={}", userId);
    }

    // ==================== Private Helpers ====================

    private String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 使用 LINE Reply API 回覆訊息
     */
    private void replyText(String replyToken, String text) {
        if (replyToken == null) return;

        String json = String.format("""
                {
                  "replyToken": "%s",
                  "messages": [{"type": "text", "text": "%s"}]
                }""", replyToken, escapeJson(text));

        Request request = new Request.Builder()
                .url(REPLY_API_URL)
                .addHeader("Authorization", "Bearer " + lineConfig.getChannelAccessToken())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("LINE Reply 失敗: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.warn("LINE Reply 異常: HTTP {} - {}",
                                response.code(),
                                response.body() != null ? response.body().string() : "no body");
                    }
                } catch (IOException e) {
                    log.warn("讀取 LINE Reply 回應失敗: {}", e.getMessage());
                }
            }
        });
    }

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
