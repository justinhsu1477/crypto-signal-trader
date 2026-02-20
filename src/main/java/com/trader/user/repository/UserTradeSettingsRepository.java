package com.trader.user.repository;

import com.trader.user.entity.UserTradeSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserTradeSettingsRepository extends JpaRepository<UserTradeSettings, String> {
    // PK 是 userId，findById(userId) 直接可用
}
