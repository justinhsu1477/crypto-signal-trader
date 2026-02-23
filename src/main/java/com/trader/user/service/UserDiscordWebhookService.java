package com.trader.user.service;

import com.trader.user.entity.UserDiscordWebhook;
import com.trader.user.repository.UserDiscordWebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDiscordWebhookService {

    private final UserDiscordWebhookRepository webhookRepository;

    /**
     * 建立或更新用戶的 webhook
     * - 如果已有啟用的 webhook，停用舊的
     * - 建立新 webhook 為啟用狀態
     */
    public UserDiscordWebhook createOrUpdateWebhook(String userId, String webhookUrl, String name) {
        // 停用舊的啟用 webhook
        Optional<UserDiscordWebhook> existing = webhookRepository
                .findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(userId);
        if (existing.isPresent()) {
            UserDiscordWebhook old = existing.get();
            old.setEnabled(false);
            webhookRepository.save(old);
            log.info("停用舊 webhook: userId={} webhookId={}", userId, old.getWebhookId());
        }

        // 建立新 webhook
        UserDiscordWebhook webhook = UserDiscordWebhook.builder()
                .webhookId(UUID.randomUUID().toString())
                .userId(userId)
                .webhookUrl(webhookUrl)
                .name(name != null ? name : "Discord Webhook")
                .enabled(true)
                .build();

        webhookRepository.save(webhook);
        log.info("新增 webhook: userId={} webhookId={}", userId, webhook.getWebhookId());

        return webhook;
    }

    /**
     * 取得用戶主要的啟用 webhook（用於廣播）
     */
    public Optional<UserDiscordWebhook> getPrimaryWebhook(String userId) {
        return webhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(userId);
    }

    /**
     * 查詢用戶所有啟用的 webhook
     */
    public List<UserDiscordWebhook> getEnabledWebhooks(String userId) {
        return webhookRepository.findByUserIdAndEnabledTrue(userId);
    }

    /**
     * 查詢用戶所有 webhook
     */
    public List<UserDiscordWebhook> getAllWebhooks(String userId) {
        return webhookRepository.findByUserId(userId);
    }

    /**
     * 停用 webhook（含所有權驗證）
     *
     * @throws IllegalArgumentException 若 webhook 不存在或不屬於該用戶
     */
    public void disableWebhook(String userId, String webhookId) {
        UserDiscordWebhook webhook = webhookRepository.findByWebhookIdAndUserId(webhookId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Webhook 不存在或無權操作: webhookId=" + webhookId));

        webhook.setEnabled(false);
        webhookRepository.save(webhook);
        log.info("停用 webhook: userId={} webhookId={}", userId, webhookId);
    }

    /**
     * 刪除 webhook（含所有權驗證）
     *
     * @throws IllegalArgumentException 若 webhook 不存在或不屬於該用戶
     */
    public void deleteWebhook(String userId, String webhookId) {
        UserDiscordWebhook webhook = webhookRepository.findByWebhookIdAndUserId(webhookId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Webhook 不存在或無權操作: webhookId=" + webhookId));

        webhookRepository.delete(webhook);
        log.info("刪除 webhook: userId={} webhookId={}", userId, webhookId);
    }
}
