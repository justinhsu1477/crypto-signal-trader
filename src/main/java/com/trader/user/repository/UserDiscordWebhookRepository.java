package com.trader.user.repository;

import com.trader.user.entity.UserDiscordWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDiscordWebhookRepository extends JpaRepository<UserDiscordWebhook, String> {

    /**
     * Batch 查詢：取得所有有啟用 webhook 的 userId
     * 用於 DailyReportService 過濾無 webhook 用戶（避免 fallback 到全局 webhook 洗版）
     */
    @Query("SELECT DISTINCT w.userId FROM UserDiscordWebhook w WHERE w.enabled = true")
    List<String> findUserIdsWithEnabledWebhook();

    /**
     * 查詢用戶所有啟用的 webhook
     */
    List<UserDiscordWebhook> findByUserIdAndEnabledTrue(String userId);

    /**
     * 查詢用戶所有 webhook
     */
    List<UserDiscordWebhook> findByUserId(String userId);

    /**
     * 查詢用戶主要 webhook（用於廣播）
     * 按更新時間排序，取最新的已啟用 webhook
     */
    Optional<UserDiscordWebhook> findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(String userId);

    /**
     * 用 webhookId + userId 查詢（所有權驗證用）
     */
    Optional<UserDiscordWebhook> findByWebhookIdAndUserId(String webhookId, String userId);

    /**
     * 刪除用戶所有 Discord Webhook（帳號刪除時使用）
     */
    void deleteByUserId(String userId);
}
