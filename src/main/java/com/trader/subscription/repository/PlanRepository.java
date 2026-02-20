package com.trader.subscription.repository;

import com.trader.subscription.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, String> {

    List<Plan> findByActiveTrue();

    Optional<Plan> findByPlanIdAndActiveTrue(String planId);
}
