package com.trader.trading.repository;

import com.trader.trading.entity.BroadcastLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BroadcastLogRepository extends JpaRepository<BroadcastLog, Long> {

    /**
     * 分頁查詢廣播紀錄（依建立時間倒序）
     */
    Page<BroadcastLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 依來源篩選廣播紀錄（分頁，依建立時間倒序）
     */
    Page<BroadcastLog> findBySourceAuthorOrderByCreatedAtDesc(String sourceAuthor, Pageable pageable);

    /**
     * 依日期範圍篩選廣播紀錄（分頁，依建立時間倒序）
     */
    Page<BroadcastLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 依來源 + 日期範圍篩選廣播紀錄（分頁，依建立時間倒序）
     */
    Page<BroadcastLog> findBySourceAuthorAndCreatedAtBetweenOrderByCreatedAtDesc(
            String sourceAuthor, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 依來源（模糊搜尋）篩選廣播紀錄（分頁，依建立時間倒序）
     */
    Page<BroadcastLog> findBySourceAuthorContainingIgnoreCaseOrderByCreatedAtDesc(
            String sourceAuthor, Pageable pageable);

    /**
     * 依來源（模糊搜尋）+ 日期範圍篩選（分頁，依建立時間倒序）
     */
    Page<BroadcastLog> findBySourceAuthorContainingIgnoreCaseAndCreatedAtBetweenOrderByCreatedAtDesc(
            String sourceAuthor, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 查詢所有不重複的來源名稱（供篩選下拉選單用）
     */
    @Query("SELECT DISTINCT b.sourceAuthor FROM BroadcastLog b WHERE b.sourceAuthor IS NOT NULL ORDER BY b.sourceAuthor")
    List<String> findDistinctSourceAuthors();

    /**
     * 查詢指定時間範圍的廣播紀錄（日報用）
     */
    List<BroadcastLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
