package com.trader.trading.repository;

import com.trader.trading.entity.AnalystDailyMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalystDailyMessageRepository extends JpaRepository<AnalystDailyMessage, Long> {

    Optional<AnalystDailyMessage> findByAnalystNameAndMessageDate(String analystName, LocalDate messageDate);

    /**
     * Pessimistic lock 版本 — 防止併發 insert 時的 UNIQUE constraint 衝突
     * SELECT ... FOR UPDATE：若記錄存在則鎖定，不存在則後續 insert 由 DB 的 UNIQUE constraint 保護
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM AnalystDailyMessage m WHERE m.analystName = :analystName AND m.messageDate = :messageDate")
    Optional<AnalystDailyMessage> findWithLockByAnalystNameAndMessageDate(String analystName, LocalDate messageDate);

    List<AnalystDailyMessage> findByMessageDate(LocalDate messageDate);

    List<AnalystDailyMessage> findByMessageDateOrderByAnalystName(LocalDate messageDate);
}
