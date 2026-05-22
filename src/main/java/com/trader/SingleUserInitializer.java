package com.trader;

import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 單用戶模式啟動時自動建立系統用戶
 *
 * 問題背景：trades 表有 FK 約束 (user_id → users.user_id)，
 * 如果 TRADING_USER_ID 對應的用戶不存在，所有 recordEntry 會被 FK 拒絕，
 * 導致熔斷/DCA 層數/fallback/統計全部失效。
 *
 * 解法：啟動時確保 TRADING_USER_ID 的用戶存在於 users 表。
 *
 * <p>{@link ConditionalOnProperty} 限制：只有 {@code multi-user.enabled=false}
 *（或 property 未設）時這個 bean 才存在。multi-user prod 模式下這個 class
 * 完全不會被載入，避免 runtime if-check 散落各處。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "multi-user.enabled", havingValue = "false", matchIfMissing = true)
public class SingleUserInitializer {

    private final UserRepository userRepository;

    @Value("${trading.user-id:system-trader}")
    private String tradingUserId;

    @PostConstruct
    public void ensureSingleUserExists() {
        // 空字串 fallback 到預設值
        if (tradingUserId == null || tradingUserId.isBlank()) {
            tradingUserId = "system-trader";
            log.warn("TRADING_USER_ID 為空，使用預設值: {}", tradingUserId);
        }

        if (userRepository.existsById(tradingUserId)) {
            log.info("單用戶模式: 用戶 {} 已存在", tradingUserId);
            return;
        }

        User systemUser = User.builder()
                .userId(tradingUserId)
                .email(tradingUserId + "@system.local")
                .passwordHash("$system-auto-created$")
                .name("System Trader")
                .role(User.Role.ADMIN)
                .enabled(true)
                .autoTradeEnabled(true)
                .build();

        userRepository.save(systemUser);
        log.info("單用戶模式: 自動建立系統用戶 {} ({})", tradingUserId, systemUser.getEmail());
    }
}
