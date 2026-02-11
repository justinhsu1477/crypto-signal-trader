package com.trader.repository;

import com.trader.entity.TradeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeEventRepository extends JpaRepository<TradeEvent, Long> {

    /**
     * 查詢某筆交易的所有事件，依時間排序
     */
    List<TradeEvent> findByTradeIdOrderByTimestampAsc(String tradeId);

    /**
     * 依事件類型查詢
     */
    List<TradeEvent> findByEventType(String eventType);

    /**
     * 查詢某筆交易的某種事件
     */
    List<TradeEvent> findByTradeIdAndEventType(String tradeId, String eventType);
}
