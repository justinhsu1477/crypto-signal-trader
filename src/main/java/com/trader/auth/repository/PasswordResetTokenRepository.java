package com.trader.auth.repository;

import com.trader.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 密碼重設 Token Repository
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * 依 token hash 查詢未使用的 token
     */
    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    /**
     * 統計指定時間之後的請求次數（rate limit 用）
     */
    long countByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);
}
