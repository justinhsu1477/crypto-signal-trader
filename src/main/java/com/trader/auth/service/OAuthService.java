package com.trader.auth.service;

import com.trader.auth.dto.LineProfile;
import com.trader.auth.dto.LineTokenResponse;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.entity.OAuthProviderType;
import com.trader.auth.entity.OAuthState;
import com.trader.auth.entity.UserOAuthProvider;
import com.trader.auth.repository.OAuthStateRepository;
import com.trader.auth.repository.UserOAuthProviderRepository;
import com.trader.shared.config.AppConstants;
import com.trader.user.entity.User;
import com.trader.user.entity.UserLineBinding;
import com.trader.user.repository.UserLineBindingRepository;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 第三方登入核心服務
 *
 * 負責：
 * 1. 生成授權 URL + CSRF state
 * 2. 處理 callback（換 token、解析帳號）
 * 3. 生成 one-time ticket 供前端交換 Cookie
 * 4. 定期清理過期 state
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final int STATE_EXPIRY_MINUTES = 10;
    private static final int TICKET_EXPIRY_SECONDS = 60;

    private final LineOAuthClient lineOAuthClient;
    private final AuthService authService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserLineBindingRepository lineBindingRepository;
    private final UserOAuthProviderRepository oauthProviderRepository;
    private final OAuthStateRepository stateRepository;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * One-time ticket → userId 映射（記憶體暫存，60 秒過期）
     * key = ticket, value = TicketInfo(userId, expiresAt)
     */
    private final ConcurrentHashMap<String, TicketInfo> ticketStore = new ConcurrentHashMap<>();

    // ===== Step 1: 生成授權 URL =====

    /**
     * 生成 LINE Login 授權 URL，含 CSRF state
     */
    @Transactional
    public String generateLineAuthUrl() {
        String state = generateRandomState();

        OAuthState oAuthState = OAuthState.builder()
                .state(state)
                .provider(OAuthProviderType.LINE)
                .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(STATE_EXPIRY_MINUTES))
                .build();
        stateRepository.save(oAuthState);

        return lineOAuthClient.buildAuthorizationUrl(state);
    }

    // ===== Step 2: 處理 callback =====

    /**
     * 處理 LINE OAuth callback
     *
     * @return one-time ticket（前端用此 ticket 交換 HttpOnly Cookie）
     */
    @Transactional
    public String handleLineCallback(String code, String state) {
        // 1. 驗證 state（CSRF 保護）
        OAuthState oAuthState = stateRepository.findById(state)
                .orElseThrow(() -> new IllegalArgumentException("無效的 OAuth state"));

        if (oAuthState.isExpired()) {
            stateRepository.delete(oAuthState);
            throw new IllegalArgumentException("OAuth state 已過期");
        }

        if (oAuthState.getProvider() != OAuthProviderType.LINE) {
            throw new IllegalArgumentException("OAuth state provider 不符");
        }

        // 一次性使用：立即刪除
        stateRepository.delete(oAuthState);

        // 2. 用 code 換取 token
        LineTokenResponse tokenResponse = lineOAuthClient.exchangeCode(code);

        // 3. 取得 LINE Profile
        LineProfile profile = lineOAuthClient.getProfile(tokenResponse.getAccessToken());
        String lineUserId = profile.getUserId();
        String displayName = profile.getDisplayName();

        // 3.5 從 ID Token 解析 email（用戶可能未授權 → null）
        String lineEmail = lineOAuthClient.extractEmailFromIdToken(tokenResponse.getIdToken());

        log.info("LINE Login callback: lineUserId={} displayName={} email={}",
                lineUserId, displayName, lineEmail != null ? lineEmail : "(未提供)");

        // 4. 解析帳號（找到或建立用戶）
        User user = resolveUser(lineUserId, displayName, lineEmail, tokenResponse);

        // 5. 確保 UserLineBinding 存在（自動綁定 LINE 通知）
        ensureLineBinding(user.getUserId(), lineUserId, displayName);

        // 6. 生成 one-time ticket
        return generateTicket(user.getUserId());
    }

    // ===== Step 3: Ticket 交換 =====

    /**
     * 用 one-time ticket 交換 LoginResponse（含 JWT）
     */
    public LoginResponse completeLogin(String ticket) {
        TicketInfo info = ticketStore.remove(ticket);
        if (info == null) {
            throw new IllegalArgumentException("無效的 OAuth ticket");
        }
        if (info.isExpired()) {
            throw new IllegalArgumentException("OAuth ticket 已過期");
        }

        User user = userRepository.findById(info.userId)
                .orElseThrow(() -> new IllegalArgumentException("用戶不存在"));

        return authService.loginByOAuth(user);
    }

    // ===== 帳號解析邏輯 =====

    /**
     * 按優先順序解析帳號：
     * 1. user_oauth_providers 找到 (LINE, lineUserId) → 直接登入
     * 2. user_line_bindings 找到 lineUserId → 登入 + 補建 OAuth 記錄
     * 3. LINE 回傳 email → users 表找到已驗證的同 email 帳號 → 自動合併
     * 4. 都找不到 → 建立新用戶
     *
     * 安全：Path 3 只在既有帳號 emailVerified=true 時才合併，
     *       防止攻擊者用假 email 的 LINE 帳號接管帳號。
     */
    private User resolveUser(String lineUserId, String displayName,
                             String lineEmail, LineTokenResponse tokenResponse) {
        // 路徑 1: 已有 OAuth 綁定
        Optional<UserOAuthProvider> existingOAuth = oauthProviderRepository
                .findByProviderAndProviderUserId(OAuthProviderType.LINE, lineUserId);
        if (existingOAuth.isPresent()) {
            String userId = existingOAuth.get().getUserId();
            log.info("LINE Login 路徑 1: 已有 OAuth 綁定 → userId={}", userId);
            // 更新 token
            UserOAuthProvider provider = existingOAuth.get();
            provider.setAccessToken(tokenResponse.getAccessToken());
            if (tokenResponse.getRefreshToken() != null) {
                provider.setRefreshToken(tokenResponse.getRefreshToken());
            }
            provider.setDisplayName(displayName);
            oauthProviderRepository.save(provider);
            return userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("OAuth 綁定的用戶不存在"));
        }

        // 路徑 2: 已有 LINE Binding（8 碼綁定碼建立的）
        Optional<UserLineBinding> existingBinding = lineBindingRepository.findByLineUserId(lineUserId);
        if (existingBinding.isPresent()) {
            String userId = existingBinding.get().getUserId();
            log.info("LINE Login 路徑 2: 已有 LINE Binding → 補建 OAuth 記錄 userId={}", userId);
            createOAuthProvider(userId, lineUserId, displayName, tokenResponse);
            return userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("LINE 綁定的用戶不存在"));
        }

        // 路徑 3: LINE 回傳 email → 比對已驗證的既有帳號
        if (lineEmail != null && !lineEmail.isBlank()) {
            Optional<User> emailMatch = userRepository.findByEmailIgnoreCase(lineEmail);
            if (emailMatch.isPresent()) {
                User existingUser = emailMatch.get();
                // 安全檢查：只有 emailVerified 且帳號啟用才自動合併
                if (existingUser.isEmailVerified() && existingUser.isEnabled()) {
                    log.info("LINE Login 路徑 3: email 比對成功 → 自動合併 userId={} email={}",
                            existingUser.getUserId(), lineEmail);
                    createOAuthProvider(existingUser.getUserId(), lineUserId, displayName, tokenResponse);
                    return existingUser;
                } else {
                    log.warn("LINE Login 路徑 3: email 比對到帳號但不符合合併條件 " +
                                    "(emailVerified={} enabled={}) → 建立新帳號",
                            existingUser.isEmailVerified(), existingUser.isEnabled());
                }
            }
        }

        // 路徑 4: 新用戶
        log.info("LINE Login 路徑 4: 新用戶 → 建立帳號 displayName={}", displayName);
        User newUser = User.builder()
                .userId(UUID.randomUUID().toString())
                .email(null)  // 純 LINE 用戶無 email
                .passwordHash(null)  // 無密碼
                .name(displayName)
                .emailVerified(false)
                .autoTradeEnabled(false)
                .lineNotificationEnabled(true)
                .build();
        userRepository.save(newUser);

        createOAuthProvider(newUser.getUserId(), lineUserId, displayName, tokenResponse);

        return newUser;
    }

    private void createOAuthProvider(String userId, String lineUserId, String displayName,
                                     LineTokenResponse tokenResponse) {
        UserOAuthProvider provider = UserOAuthProvider.builder()
                .userId(userId)
                .provider(OAuthProviderType.LINE)
                .providerUserId(lineUserId)
                .displayName(displayName)
                .accessToken(tokenResponse.getAccessToken())
                .refreshToken(tokenResponse.getRefreshToken())
                .build();
        oauthProviderRepository.save(provider);
    }

    private void ensureLineBinding(String userId, String lineUserId, String displayName) {
        Optional<UserLineBinding> existing = lineBindingRepository.findByLineUserId(lineUserId);
        if (existing.isPresent()) {
            // 已存在，確保 enabled
            UserLineBinding binding = existing.get();
            if (!binding.isEnabled()) {
                binding.setEnabled(true);
                binding.setUpdatedAt(LocalDateTime.now(AppConstants.ZONE_ID));
                lineBindingRepository.save(binding);
                log.info("LINE Login: 重新啟用 LINE Binding userId={}", userId);
            }
            return;
        }

        // 建立新 binding
        UserLineBinding binding = UserLineBinding.builder()
                .userId(userId)
                .lineUserId(lineUserId)
                .displayName(displayName)
                .enabled(true)
                .linkedAt(LocalDateTime.now(AppConstants.ZONE_ID))
                .updatedAt(LocalDateTime.now(AppConstants.ZONE_ID))
                .build();
        lineBindingRepository.save(binding);
        log.info("LINE Login: 自動建立 LINE Binding userId={}", userId);
    }

    // ===== Ticket 管理 =====

    private String generateTicket(String userId) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        ticketStore.put(ticket, new TicketInfo(
                userId,
                System.currentTimeMillis() + TICKET_EXPIRY_SECONDS * 1000L
        ));

        return ticket;
    }

    private String generateRandomState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ===== 定期清理 =====

    @Scheduled(fixedRate = 600_000)  // 每 10 分鐘
    @Transactional
    public void cleanupExpiredStates() {
        int deleted = stateRepository.deleteExpired(LocalDateTime.now(AppConstants.ZONE_ID));
        if (deleted > 0) {
            log.info("已清除 {} 筆過期 OAuth state", deleted);
        }

        // 清理過期 ticket
        long now = System.currentTimeMillis();
        ticketStore.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    // ===== Inner class =====

    private record TicketInfo(String userId, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
