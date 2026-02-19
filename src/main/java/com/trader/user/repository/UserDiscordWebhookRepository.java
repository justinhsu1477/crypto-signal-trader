package com.trader.user.repository;

import com.trader.user.entity.UserDiscordWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDiscordWebhookRepository extends JpaRepository<UserDiscordWebhook, String> {

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
}
