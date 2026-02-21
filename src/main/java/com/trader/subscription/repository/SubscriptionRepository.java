package com.trader.subscription.repository;

import com.trader.subscription.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserId(String userId);

    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND s.status IN ('ACTIVE', 'TRIALING')")
    Optional<Subscription> findActiveByUserId(String userId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);
}
