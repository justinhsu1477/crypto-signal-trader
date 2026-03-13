package com.trader.trading.repository;

import com.trader.trading.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<Trade, String> {

    /**
     * 依狀態查詢交易紀錄（排除模擬交易）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = :status AND t.simulated = false")
    List<Trade> findByStatus(@Param("status") String status);

    /**
     * 依交易對 + 狀態查詢（排除模擬交易）
     */
    @Query("SELECT t FROM Trade t WHERE t.symbol = :symbol AND t.status = :status AND t.simulated = false")
    List<Trade> findBySymbolAndStatus(@Param("symbol") String symbol, @Param("status") String status);

    /**
     * 找某交易對目前唯一的 OPEN 交易
     */
    default Optional<Trade> findOpenTrade(String symbol) {
        List<Trade> openTrades = findBySymbolAndStatus(symbol, "OPEN");
        return openTrades.isEmpty() ? Optional.empty() : Optional.of(openTrades.get(0));
    }

    /**
     * 找某交易對 OPEN 或 PENDING_CLOSE 的交易（供 WebSocket 更新用）
     * PENDING_CLOSE = MARKET 平倉單已送出但 exitPrice 尚未從 WebSocket 取得真實值
     */
    @Query("SELECT t FROM Trade t WHERE t.symbol = :symbol AND t.status IN ('OPEN', 'PENDING_CLOSE') AND t.simulated = false ORDER BY t.updatedAt DESC")
    List<Trade> findOpenOrPendingCloseTrade(@Param("symbol") String symbol);

    /**
     * 查詢所有 OPEN 的交易（用於無幣種訊號的 fallback）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'OPEN' AND t.simulated = false ORDER BY t.updatedAt DESC")
    List<Trade> findAllOpenTrades();

    /**
     * 依狀態查詢，依建立時間倒序（排除模擬交易）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = :status AND t.simulated = false ORDER BY t.createdAt DESC")
    List<Trade> findByStatusOrderByCreatedAtDesc(@Param("status") String status);

    /**
     * 查詢所有紀錄，依建立時間倒序（排除模擬交易）
     */
    @Query("SELECT t FROM Trade t WHERE t.simulated = false ORDER BY t.createdAt DESC")
    List<Trade> findAllByOrderByCreatedAtDesc();

    /**
     * 統計已平倉交易中獲利的筆數（netProfit > 0）
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.netProfit > 0 AND t.simulated = false")
    long countWinningTrades();

    /**
     * 統計已平倉交易總筆數（排除模擬交易）
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.simulated = false")
    long countClosedTrades();

    /**
     * 至少有一筆已平倉交易的不同用戶數（Funnel Stats 用，排除模擬交易）
     */
    @Query("SELECT COUNT(DISTINCT t.userId) FROM Trade t WHERE t.status = 'CLOSED' AND t.simulated = false")
    long countDistinctUserIdsWithClosedTrades();

    /**
     * 已平倉交易的淨利總和（排除模擬交易）
     */
    @Query("SELECT COALESCE(SUM(t.netProfit), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.simulated = false")
    double sumNetProfit();

    /**
     * 已平倉交易中，獲利交易的毛利總和（用於 Profit Factor，排除模擬交易）
     */
    @Query("SELECT COALESCE(SUM(t.grossProfit), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.grossProfit > 0 AND t.simulated = false")
    double sumGrossWins();

    /**
     * 已平倉交易中，虧損交易的毛利總和（絕對值，用於 Profit Factor，排除模擬交易）
     */
    @Query("SELECT COALESCE(SUM(ABS(t.grossProfit)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.grossProfit < 0 AND t.simulated = false")
    double sumGrossLosses();

    /**
     * 手續費總和（排除模擬交易）
     */
    @Query("SELECT COALESCE(SUM(t.commission), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.simulated = false")
    double sumCommission();

    /**
     * 查詢某幣種 OPEN 交易的 DCA 補倉次數（全局，單用戶模式）
     */
    @Query("SELECT COALESCE(t.dcaCount, 0) FROM Trade t WHERE t.symbol = :symbol AND t.status = 'OPEN' AND t.simulated = false")
    Optional<Integer> findDcaCountBySymbol(@Param("symbol") String symbol);

    /**
     * 查詢用戶某幣種 OPEN 交易的 DCA 補倉次數（多用戶隔離）
     */
    @Query("SELECT COALESCE(t.dcaCount, 0) FROM Trade t WHERE t.userId = :userId AND t.symbol = :symbol AND t.status = 'OPEN'")
    Optional<Integer> findUserDcaCountBySymbol(@Param("userId") String userId, @Param("symbol") String symbol);

    /**
     * 檢查指定時間窗口內是否存在相同 signalHash 的交易（用於去重）
     */
    boolean existsBySignalHashAndCreatedAtAfter(String signalHash, LocalDateTime after);

    /**
     * 查詢指定時間後已平倉的交易（用於每日虧損熔斷）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :since AND t.simulated = false")
    List<Trade> findClosedTradesAfter(@Param("since") LocalDateTime since);

    /**
     * 查詢指定時間範圍內已平倉的交易（用於每日摘要報告）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :from AND t.exitTime < :to AND t.simulated = false")
    List<Trade> findClosedTradesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 查詢指定時間後已平倉的所有交易（用於績效統計）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :since AND t.simulated = false ORDER BY t.exitTime ASC")
    List<Trade> findClosedTradesAfterOrderByExitTime(@Param("since") LocalDateTime since);

    /**
     * 已平倉交易（倒序，用於交易歷史分頁）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.simulated = false ORDER BY t.exitTime DESC")
    List<Trade> findAllClosedTradesDesc();

    /**
     * 找某交易對最近被啟動對帳 CANCELLED 的交易（fallback：被誤標時可恢復）
     * 只找 4 小時內且 exitReason = STALE_CLEANUP_STARTUP 的交易，按更新時間倒序
     */
    @Query("SELECT t FROM Trade t WHERE t.symbol = :symbol AND t.status = 'CANCELLED' " +
           "AND t.exitReason = 'STALE_CLEANUP_STARTUP' AND t.updatedAt >= :since ORDER BY t.updatedAt DESC")
    List<Trade> findRecentlyStaleCleanedTrades(@Param("symbol") String symbol, @Param("since") LocalDateTime since);

    /**
     * 找用戶某交易對最近被啟動對帳 CANCELLED 的交易
     */
    @Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.symbol = :symbol AND t.status = 'CANCELLED' " +
           "AND t.exitReason = 'STALE_CLEANUP_STARTUP' AND t.updatedAt >= :since ORDER BY t.updatedAt DESC")
    List<Trade> findUserRecentlyStaleCleanedTrades(@Param("userId") String userId, @Param("symbol") String symbol, @Param("since") LocalDateTime since);

    // ========== entryOrderId 查詢（LIMIT 入場成交用） ==========

    /**
     * 依 entryOrderId 找 OPEN 交易（全局查詢，單用戶模式）
     * 用於 WebSocket LIMIT FILLED 事件判斷是否為入場單成交
     */
    Optional<Trade> findByEntryOrderIdAndStatus(String entryOrderId, String status);

    /**
     * 依 userId + entryOrderId 找 OPEN 交易（多用戶隔離）
     */
    Optional<Trade> findByUserIdAndEntryOrderIdAndStatus(String userId, String entryOrderId, String status);

    // ========== userId 相關查詢（多用戶支援） ==========

    /**
     * 依用戶 ID 查詢所有交易
     */
    List<Trade> findByUserId(String userId);

    /**
     * 依用戶 ID 和狀態查詢
     */
    List<Trade> findByUserIdAndStatus(String userId, String status);

    /**
     * 統計用戶指定狀態的交易數量（避免 findByUserIdAndStatus().size() 的 N+1 問題）
     */
    long countByUserIdAndStatus(String userId, String status);

    /**
     * 統計全局指定狀態的交易數量（排除模擬交易）
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = :status AND t.simulated = false")
    long countByStatus(@Param("status") String status);

    /**
     * 依用戶 ID 和交易對查詢
     */
    List<Trade> findByUserIdAndSymbol(String userId, String symbol);

    /**
     * 依用戶 ID、交易對、狀態查詢（最常用：找用戶的 OPEN 持倉）
     */
    List<Trade> findByUserIdAndSymbolAndStatus(String userId, String symbol, String status);

    /**
     * 找用戶某交易對目前唯一的 OPEN 交易
     */
    default Optional<Trade> findUserOpenTrade(String userId, String symbol) {
        List<Trade> openTrades = findByUserIdAndSymbolAndStatus(userId, symbol, "OPEN");
        return openTrades.isEmpty() ? Optional.empty() : Optional.of(openTrades.get(0));
    }

    /**
     * 找用戶某交易對 OPEN 或 PENDING_CLOSE 的交易（供 WebSocket 更新用）
     */
    @Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.symbol = :symbol AND t.status IN ('OPEN', 'PENDING_CLOSE') ORDER BY t.updatedAt DESC")
    List<Trade> findUserOpenOrPendingCloseTrade(@Param("userId") String userId, @Param("symbol") String symbol);

    /**
     * 統計用戶已平倉交易中獲利的筆數
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' AND t.netProfit > 0")
    long countUserWinningTrades(@Param("userId") String userId);

    /**
     * 統計用戶已平倉交易總筆數
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED'")
    long countUserClosedTrades(@Param("userId") String userId);

    /**
     * 用戶已平倉交易的淨利總和
     */
    @Query("SELECT COALESCE(SUM(t.netProfit), 0) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED'")
    double sumUserNetProfit(@Param("userId") String userId);

    /**
     * 用戶指定時間後已平倉的交易
     */
    @Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' AND t.exitTime >= :since ORDER BY t.exitTime DESC")
    List<Trade> findUserClosedTradesAfter(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /**
     * 用戶已平倉交易（倒序，用於歷史分頁）
     */
    @Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' ORDER BY t.exitTime DESC")
    List<Trade> findUserAllClosedTradesDesc(@Param("userId") String userId);

    /**
     * 用戶已平倉交易中，獲利交易的毛利總和（用於 Profit Factor）
     */
    @Query("SELECT COALESCE(SUM(t.grossProfit), 0) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' AND t.grossProfit > 0")
    double sumUserGrossWins(@Param("userId") String userId);

    /**
     * 用戶已平倉交易中，虧損交易的毛利總和（絕對值，用於 Profit Factor）
     */
    @Query("SELECT COALESCE(SUM(ABS(t.grossProfit)), 0) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' AND t.grossProfit < 0")
    double sumUserGrossLosses(@Param("userId") String userId);

    /**
     * 用戶手續費總和
     */
    @Query("SELECT COALESCE(SUM(t.commission), 0) FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED'")
    double sumUserCommission(@Param("userId") String userId);

    /**
     * 用戶指定時間範圍內已平倉的交易
     */
    @Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.status = 'CLOSED' AND t.exitTime >= :from AND t.exitTime < :to")
    List<Trade> findUserClosedTradesBetween(@Param("userId") String userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 依用戶 ID 和狀態查詢，依建立時間倒序
     */
    List<Trade> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    /**
     * 依用戶 ID 查詢所有交易，依建立時間倒序
     */
    List<Trade> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 批次更新 AI 信號評分
     * 條件：同 symbol + OPEN 狀態 + 尚未評分 + 入場時間在指定時間之後
     * 用於廣播跟單後批次寫入 AI 評分結果
     */
    @Modifying
    @Transactional
    @Query("UPDATE Trade t SET t.aiConfidence = :confidence, t.aiReasoning = :reasoning " +
           "WHERE t.symbol = :symbol AND t.status = 'OPEN' AND t.aiConfidence IS NULL " +
           "AND t.entryTime > :since AND t.simulated = false")
    int updateAiScore(@Param("symbol") String symbol,
                      @Param("confidence") Integer confidence,
                      @Param("reasoning") String reasoning,
                      @Param("since") LocalDateTime since);

    // ========== 批次聚合查詢（解決 Admin system-overview N+1 問題） ==========

    /**
     * 批次聚合所有用戶的交易統計（一次查詢取代 N 次 per-user 查詢）
     *
     * 面試重點：N+1 問題的經典解法 — 用 GROUP BY 批次聚合取代 loop 內逐一查詢
     *
     * 回傳 Object[]：
     *   [0] userId(String), [1] closedCount(Long), [2] winCount(Long),
     *   [3] totalNetProfit(Double), [4] openCount(Long)
     */
    @Query(value = """
            SELECT user_id,
                   COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed_count,
                   COUNT(*) FILTER (WHERE status = 'CLOSED' AND net_profit > 0) AS win_count,
                   COALESCE(SUM(net_profit) FILTER (WHERE status = 'CLOSED'), 0) AS total_net_profit,
                   COUNT(*) FILTER (WHERE status = 'OPEN') AS open_count
            FROM trades
            WHERE simulated = false
            GROUP BY user_id
            """, nativeQuery = true)
    List<Object[]> aggregateStatsPerUser();

    /**
     * 批次聚合所有用戶的今日交易統計
     *
     * 回傳 Object[]：
     *   [0] userId(String), [1] todayTradeCount(Long), [2] todayNetProfit(Double)
     */
    @Query(value = """
            SELECT user_id,
                   COUNT(*) AS today_trade_count,
                   COALESCE(SUM(net_profit), 0) AS today_net_profit
            FROM trades
            WHERE status = 'CLOSED' AND exit_time >= :since AND simulated = false
            GROUP BY user_id
            """, nativeQuery = true)
    List<Object[]> aggregateTodayStatsPerUser(@Param("since") LocalDateTime since);

    /**
     * 通用時間區間批次聚合 — 回傳指定時間之後的交易統計
     *
     * 回傳 Object[]：
     *   [0] userId(String), [1] tradeCount(Long), [2] netProfit(Double)
     */
    @Query(value = """
            SELECT user_id,
                   COUNT(*) AS trade_count,
                   COALESCE(SUM(net_profit), 0) AS net_profit
            FROM trades
            WHERE status = 'CLOSED' AND exit_time >= :since AND simulated = false
            GROUP BY user_id
            """, nativeQuery = true)
    List<Object[]> aggregateStatsPerUserSince(@Param("since") LocalDateTime since);

    // ========== 用戶健康度查詢 ==========

    // ========== 訊號來源績效查詢 ==========

    /**
     * 按訊號來源聚合績效 — 從 trades 表計算勝率/PnL
     *
     * 回傳 Object[]：
     *   [0] tradeCount(Long), [1] winCount(Long),
     *   [2] totalPnl(Double), [3] avgPnl(Double)
     */
    @Query(value = """
            SELECT
                COUNT(*) AS trade_count,
                COUNT(*) FILTER (WHERE net_profit > 0) AS win_count,
                COALESCE(SUM(net_profit), 0) AS total_pnl,
                COALESCE(AVG(net_profit), 0) AS avg_pnl
            FROM trades
            WHERE status = 'CLOSED'
              AND simulated = false
              AND source_channel_id = :channelId
              AND (:guildId IS NULL OR source_guild_id = :guildId)
            """, nativeQuery = true)
    Object[] getSourcePerformanceStats(@Param("channelId") String channelId,
                                       @Param("guildId") String guildId);

    /**
     * 按訊號來源聚合模擬交易績效（simulated = true）
     * 用於 SHADOW 模式頻道的績效追蹤
     */
    @Query(value = """
            SELECT
                COUNT(*) AS trade_count,
                COUNT(*) FILTER (WHERE net_profit > 0) AS win_count,
                COALESCE(SUM(net_profit), 0) AS total_pnl,
                COALESCE(AVG(net_profit), 0) AS avg_pnl,
                COALESCE(MAX(net_profit), 0) AS max_win,
                COALESCE(MIN(net_profit), 0) AS max_loss
            FROM trades
            WHERE status = 'CLOSED'
              AND simulated = true
              AND source_channel_id = :channelId
              AND (:guildId IS NULL OR source_guild_id = :guildId)
            """, nativeQuery = true)
    Object[] getSourcePaperTradeStats(@Param("channelId") String channelId,
                                      @Param("guildId") String guildId);

    /**
     * 批次取得所有用戶已平倉交易（依 exitTime DESC 排序）
     * 用於 Java 層計算 lastTradeAt 和 consecutiveLosses
     *
     * 回傳 Object[]：
     *   [0] userId(String), [1] exitTime(LocalDateTime), [2] netProfit(Double)
     */
    @Query("SELECT t.userId, t.exitTime, t.netProfit FROM Trade t " +
           "WHERE t.status = 'CLOSED' AND t.exitTime IS NOT NULL AND t.simulated = false " +
           "ORDER BY t.userId, t.exitTime DESC")
    List<Object[]> findRecentClosedTradesAllUsers();

    // ========== 模擬交易（Paper Trading）查詢 ==========

    /**
     * 查找所有 OPEN 的模擬交易（定時 TP/SL 監控用）
     */
    @Query("SELECT t FROM Trade t WHERE t.simulated = true AND t.status = 'OPEN'")
    List<Trade> findAllOpenSimulatedTrades();

    /**
     * 查找特定幣種 + 頻道的 OPEN 模擬交易
     */
    @Query("SELECT t FROM Trade t WHERE t.simulated = true AND t.status = 'OPEN' " +
           "AND t.symbol = :symbol AND t.sourceChannelId = :channelId")
    Optional<Trade> findOpenSimulatedTrade(@Param("symbol") String symbol,
                                           @Param("channelId") String channelId);
}
