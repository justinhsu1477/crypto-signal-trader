package com.trader.auth.repository;

import com.trader.auth.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Email OTP 驗證碼 Repository
 */
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    /**
     * 查最新一筆未使用的驗證碼
     */
    Optional<EmailVerificationCode> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email);

    Optional<EmailVerificationCode> findTopByEmailIgnoreCaseAndUsedFalseOrderByCreatedAtDesc(String email);

    /**
     * 統計指定時間之後的發送次數（rate limit 用）
     */
    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);

    long countByEmailIgnoreCaseAndCreatedAtAfter(String email, LocalDateTime since);

    /**
     * 清除過期驗證碼
     */
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
