package com.trader.trading.repository;

import com.trader.trading.entity.BalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, Long> {

    List<BalanceSnapshot> findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String userId, LocalDate start, LocalDate end);

    List<BalanceSnapshot> findByUserIdOrderBySnapshotDateAsc(String userId);

    boolean existsByUserIdAndSnapshotDate(String userId, LocalDate date);
}
