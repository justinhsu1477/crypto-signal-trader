package com.trader.user.repository;

import com.trader.user.entity.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {

    List<UserApiKey> findByUserId(String userId);

    Optional<UserApiKey> findByUserIdAndExchange(String userId, String exchange);

    /**
     * Batch 查詢：取得所有擁有指定交易所 API Key 的 userId
     * 用於 BroadcastTradeService 避免 N+1 查詢
     */
    @Query("SELECT k.userId FROM UserApiKey k WHERE k.exchange = ?1")
    List<String> findUserIdsByExchange(String exchange);

    /**
     * Batch 查詢：取得指定交易所的所有 API Key 記錄
     * 用於 MultiUserDataStreamManager 避免 dual lookup（hasApiKey + getUserBinanceKeys）
     */
    List<UserApiKey> findByExchange(String exchange);

    /**
     * 刪除用戶所有 API Key（帳號刪除時使用）
     */
    void deleteByUserId(String userId);
}
