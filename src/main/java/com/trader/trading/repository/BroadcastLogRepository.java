package com.trader.trading.repository;

import com.trader.trading.entity.BroadcastLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BroadcastLogRepository extends JpaRepository<BroadcastLog, Long> {

    /**
     * 分頁查詢廣播紀錄（依建立時間倒序）
     */
    Page<BroadcastLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 查詢指定時間範圍的廣播紀錄（日報用）
     */
    List<BroadcastLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
