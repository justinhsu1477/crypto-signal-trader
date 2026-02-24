package com.trader.user.repository;

import com.trader.user.entity.UserNotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用戶通知偏好 Repository
 * PK 是 userId，findById(userId) 直接可用
 */
@Repository
public interface UserNotificationPreferencesRepository extends JpaRepository<UserNotificationPreferences, String> {
}
