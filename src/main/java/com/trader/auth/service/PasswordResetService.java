package com.trader.auth.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.dto.ChangePasswordRequest;
import com.trader.auth.dto.ResetPasswordRequest;
import com.trader.auth.entity.PasswordResetToken;
import com.trader.auth.repository.PasswordResetTokenRepository;
import com.trader.auth.util.EmailNormalizer;
import com.trader.shared.config.AppConstants;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密碼修改 + 忘記密碼 + 密碼重設 服務
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final ResendEmailService resendEmailService;
    private final EmailConfig emailConfig;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ========== 修改密碼（已登入用戶） ==========

    /**
     * 修改密碼（需驗證現有密碼）
     *
     * @param userId  當前登入用戶 ID
     * @param request 修改密碼請求
     */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        // 1. 驗證新密碼 == 確認密碼
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("新密碼與確認密碼不一致");
        }

        // 2. 查找用戶
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在"));

        // 3. 驗證現有密碼
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("目前密碼錯誤");
        }

        // 4. 新密碼不能與現有密碼相同
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("新密碼不能與目前密碼相同");
        }

        // 5. 更新密碼 + passwordChangedAt
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(LocalDateTime.now(AppConstants.ZONE_ID));
        userRepository.save(user);

        log.info("🔑 密碼修改成功: userId={}", userId);
    }

    // ========== 忘記密碼（發送重設連結） ==========

    /**
     * 請求密碼重設（發送 email）
     * <p>
     * 安全原則：無論 email 是否存在，都靜默返回（防枚舉）
     *
     * @param email 用戶 email
     */
    @Transactional
    public void requestPasswordReset(String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        // 1. 找用戶 — 找不到就靜默 return（防枚舉）
        var userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (userOpt.isEmpty()) {
            log.debug("密碼重設請求：email 不存在（靜默處理）: {}", normalizedEmail);
            return;
        }

        User user = userOpt.get();

        // 2. Rate limit：15 分鐘內 >= maxResetPerQuarterHour 次就靜默 return
        LocalDateTime since = LocalDateTime.now(AppConstants.ZONE_ID)
                .minusMinutes(15);
        long recentCount = resetTokenRepository.countByUserIdAndCreatedAtAfter(user.getUserId(), since);
        if (recentCount >= emailConfig.getMaxResetPerQuarterHour()) {
            log.warn("🚫 密碼重設 rate limit: userId={} count={}", user.getUserId(), recentCount);
            return;
        }

        // 3. 產生 32-byte 隨機 token → Base64URL encode
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // 4. SHA-256 hash 後存 DB
        String tokenHash = sha256(rawToken);
        LocalDateTime expiresAt = LocalDateTime.now(AppConstants.ZONE_ID)
                .plusMinutes(emailConfig.getResetTokenExpiryMinutes());

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .userId(user.getUserId())
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .build();
        resetTokenRepository.save(resetToken);

        // 5. 建構 reset URL 並寄信
        String resetUrl = emailConfig.getAppBaseUrl() + "/reset-password?token=" + rawToken;
        resendEmailService.sendPasswordResetEmail(user.getEmail(), resetUrl);

        log.info("📧 密碼重設 email 已發送: userId={}", user.getUserId());
    }

    // ========== 密碼重設（驗證 token + 設定新密碼） ==========

    /**
     * 使用 reset token 重設密碼
     *
     * @param request 重設請求（token + newPassword + confirmPassword）
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        // 1. 驗證新密碼 == 確認密碼
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("新密碼與確認密碼不一致");
        }

        // 2. SHA-256 hash token → 查 DB
        String tokenHash = sha256(request.getToken());
        PasswordResetToken resetToken = resetTokenRepository.findByTokenHashAndUsedFalse(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("連結無效或已過期"));

        // 3. 檢查過期
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now(AppConstants.ZONE_ID))) {
            throw new IllegalArgumentException("連結無效或已過期");
        }

        // 4. 標記已使用
        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        // 5. 更新密碼 + passwordChangedAt
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(LocalDateTime.now(AppConstants.ZONE_ID));
        userRepository.save(user);

        log.info("🔑 密碼重設成功: userId={}", resetToken.getUserId());
    }

    // ========== 工具方法 ==========

    /**
     * SHA-256 hash
     */
    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
