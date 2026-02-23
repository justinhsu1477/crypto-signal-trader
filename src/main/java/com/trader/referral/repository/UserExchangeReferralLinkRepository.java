package com.trader.referral.repository;

import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.entity.UserExchangeReferralLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserExchangeReferralLinkRepository extends JpaRepository<UserExchangeReferralLink, Long> {

    Optional<UserExchangeReferralLink> findByUserIdAndExchange(String userId, String exchange);

    List<UserExchangeReferralLink> findByStatus(ReferralStatus status);

    boolean existsByExchangeAndExchangeUid(String exchange, String exchangeUid);

    boolean existsByUserIdAndExchangeAndStatus(String userId, String exchange, ReferralStatus status);

    /**
     * Batch 查詢所有已驗證的 userId（廣播跟單用，避免 N+1）
     */
    @Query("SELECT l.userId FROM UserExchangeReferralLink l WHERE l.exchange = ?1 AND l.status = 'VERIFIED'")
    List<String> findVerifiedUserIds(String exchange);
}
