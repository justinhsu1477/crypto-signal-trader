package com.trader.subscription.repository;

import com.trader.subscription.entity.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    List<PaymentHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 檢查 txHash 是否已使用（防重複） */
    Optional<PaymentHistory> findByTxHash(String txHash);
}
