package com.trader.repository;

import com.trader.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<Trade, String> {

    /**
     * 依狀態查詢交易紀錄
     */
    List<Trade> findByStatus(String status);

    /**
     * 依交易對 + 狀態查詢（常用：找目前 OPEN 的持倉）
     */
    List<Trade> findBySymbolAndStatus(String symbol, String status);

    /**
     * 找某交易對目前唯一的 OPEN 交易
     */
    default Optional<Trade> findOpenTrade(String symbol) {
        List<Trade> openTrades = findBySymbolAndStatus(symbol, "OPEN");
        return openTrades.isEmpty() ? Optional.empty() : Optional.of(openTrades.get(0));
    }

    /**
     * 查詢所有 OPEN 的交易（用於無幣種訊號的 fallback）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'OPEN' ORDER BY t.updatedAt DESC")
    List<Trade> findAllOpenTrades();

    /**
     * 依狀態查詢，依建立時間倒序
     */
    List<Trade> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * 查詢所有紀錄，依建立時間倒序
     */
    List<Trade> findAllByOrderByCreatedAtDesc();

    /**
     * 統計已平倉交易中獲利的筆數（netProfit > 0）
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.netProfit > 0")
    long countWinningTrades();

    /**
     * 統計已平倉交易總筆數
     */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED'")
    long countClosedTrades();

    /**
     * 已平倉交易的淨利總和
     */
    @Query("SELECT COALESCE(SUM(t.netProfit), 0) FROM Trade t WHERE t.status = 'CLOSED'")
    double sumNetProfit();

    /**
     * 已平倉交易中，獲利交易的毛利總和（用於 Profit Factor）
     */
    @Query("SELECT COALESCE(SUM(t.grossProfit), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.grossProfit > 0")
    double sumGrossWins();

    /**
     * 已平倉交易中，虧損交易的毛利總和（絕對值，用於 Profit Factor）
     */
    @Query("SELECT COALESCE(SUM(ABS(t.grossProfit)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.grossProfit < 0")
    double sumGrossLosses();

    /**
     * 手續費總和
     */
    @Query("SELECT COALESCE(SUM(t.commission), 0) FROM Trade t WHERE t.status = 'CLOSED'")
    double sumCommission();

    /**
     * 查詢某幣種 OPEN 交易的 DCA 補倉次數
     */
    @Query("SELECT COALESCE(t.dcaCount, 0) FROM Trade t WHERE t.symbol = :symbol AND t.status = 'OPEN'")
    Optional<Integer> findDcaCountBySymbol(@Param("symbol") String symbol);

    /**
     * 檢查指定時間窗口內是否存在相同 signalHash 的交易（用於去重）
     */
    boolean existsBySignalHashAndCreatedAtAfter(String signalHash, LocalDateTime after);

    /**
     * 查詢指定時間後已平倉的交易（用於每日虧損熔斷）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :since")
    List<Trade> findClosedTradesAfter(@Param("since") LocalDateTime since);

    /**
     * 查詢指定時間範圍內已平倉的交易（用於每日摘要報告）
     */
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :from AND t.exitTime < :to")
    List<Trade> findClosedTradesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
