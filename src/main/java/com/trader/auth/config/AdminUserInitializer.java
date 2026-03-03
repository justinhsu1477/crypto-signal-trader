package com.trader.auth.config;

import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 管理員帳號自動初始化
 *
 * 系統啟動時檢查是否已有 ADMIN 角色用戶，若無則自動建立。
 * Admin 帳號不需要 Email 驗證、不需要註冊碼。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer {

    @Value("${admin.bootstrap.email:}")
    private String adminEmail;

    @Value("${admin.bootstrap.password:}")
    private String adminPassword;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostConstruct
    public void ensureAdminExists() {
        if (userRepository.existsByRole(User.Role.ADMIN)) {
            log.info("管理員帳號已存在，跳過自動建立");
            return;
        }

        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.info("未設定 admin.bootstrap.email/password，跳過管理員自動建立");
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.warn("Admin email {} 已被普通用戶佔用，無法自動建立管理員", adminEmail);
            return;
        }

        User admin = User.builder()
                .userId(UUID.randomUUID().toString())
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .name("System Admin")
                .role(User.Role.ADMIN)
                .enabled(true)
                .emailVerified(true)
                .autoTradeEnabled(false)
                .build();

        userRepository.save(admin);
        log.info("管理員帳號已自動建立: email={}", adminEmail);
    }
}
