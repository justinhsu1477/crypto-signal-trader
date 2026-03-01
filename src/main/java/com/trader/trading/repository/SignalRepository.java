package com.trader.trading.repository;

import com.trader.trading.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<Signal, String> {

    List<Signal> findBySymbol(String symbol);

    List<Signal> findBySignalHash(String signalHash);

    List<Signal> findByExecutionStatus(String executionStatus);

    /**
     * message_id 永久去重：檢查此 Discord 訊息是否已被處理過。
     * 用於 Queue Replay 場景 — 即使超過 5 分鐘 hash 去重窗口，
     * 也能透過 message_id 攔截重複訊號，防止重複下單。
     */
    boolean existsBySourceMessageId(String sourceMessageId);
}
