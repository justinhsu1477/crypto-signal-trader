package com.trader.auth.service;

import com.trader.auth.dto.LineProfile;
import com.trader.auth.dto.LineTokenResponse;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.entity.OAuthProviderType;
import com.trader.auth.entity.OAuthState;
import com.trader.auth.entity.UserOAuthProvider;
import com.trader.auth.repository.OAuthStateRepository;
import com.trader.auth.repository.UserOAuthProviderRepository;
import com.trader.notification.service.NotificationService;
import com.trader.shared.config.AppConstants;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.User;
import com.trader.user.entity.UserLineBinding;
import com.trader.user.repository.UserLineBindingRepository;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OAuthService 單元測試
 *
 * 覆蓋：授權 URL 生成、state CSRF 驗證、LINE callback 處理、
 * 帳號解析四路徑、token 加密、ticket 交換、過期清理
 */
class OAuthServiceTest {

    private LineOAuthClient lineOAuthClient;
    private AuthService authService;
    private JwtService jwtService;
    private AesEncryptionUtil aesEncryptionUtil;
    private NotificationService notificationService;
    private UserRepository userRepository;
    private UserLineBindingRepository lineBindingRepository;
    private UserOAuthProviderRepository oauthProviderRepository;
    private OAuthStateRepository stateRepository;

    private OAuthService oAuthService;

    @BeforeEach
    void setUp() {
        lineOAuthClient = mock(LineOAuthClient.class);
        authService = mock(AuthService.class);
        jwtService = mock(JwtService.class);
        aesEncryptionUtil = mock(AesEncryptionUtil.class);
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);
        oauthProviderRepository = mock(UserOAuthProviderRepository.class);
        stateRepository = mock(OAuthStateRepository.class);

        oAuthService = new OAuthService(lineOAuthClient, authService, jwtService,
                aesEncryptionUtil, notificationService, userRepository,
                lineBindingRepository, oauthProviderRepository, stateRepository);
    }

    // ===== 共用測試資料 =====

    private static final String LINE_USER_ID = "U1234567890abcdef";
    private static final String DISPLAY_NAME = "TestUser";
    private static final String USER_ID = "user-uuid-001";
    private static final String ACCESS_TOKEN = "access-token-value";
    private static final String REFRESH_TOKEN = "refresh-token-value";
    private static final String ID_TOKEN = "dummy.id.token";
    private static final String ENCRYPTED_ACCESS = "encrypted-access";
    private static final String ENCRYPTED_REFRESH = "encrypted-refresh";

    private LineTokenResponse createTokenResponse(String accessToken, String refreshToken, String idToken) {
        LineTokenResponse response = new LineTokenResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setIdToken(idToken);
        response.setExpiresIn(2592000);
        response.setTokenType("Bearer");
        response.setScope("profile openid email");
        return response;
    }

    private LineTokenResponse createDefaultTokenResponse() {
        return createTokenResponse(ACCESS_TOKEN, REFRESH_TOKEN, ID_TOKEN);
    }

    private LineProfile createLineProfile(String userId, String displayName) {
        LineProfile profile = new LineProfile();
        profile.setUserId(userId);
        profile.setDisplayName(displayName);
        return profile;
    }

    private User createExistingUser(String userId, String email, boolean emailVerified, boolean enabled) {
        return User.builder()
                .userId(userId)
                .email(email)
                .name(DISPLAY_NAME)
                .role(User.Role.USER)
                .enabled(enabled)
                .emailVerified(emailVerified)
                .build();
    }

    private UserOAuthProvider createExistingOAuthProvider(String userId, String lineUserId) {
        return UserOAuthProvider.builder()
                .id(1L)
                .userId(userId)
                .provider(OAuthProviderType.LINE)
                .providerUserId(lineUserId)
                .displayName(DISPLAY_NAME)
                .accessToken("old-encrypted-access")
                .refreshToken("old-encrypted-refresh")
                .build();
    }

    private void setupDefaultEncryption() {
        when(aesEncryptionUtil.encrypt(ACCESS_TOKEN)).thenReturn(ENCRYPTED_ACCESS);
        when(aesEncryptionUtil.encrypt(REFRESH_TOKEN)).thenReturn(ENCRYPTED_REFRESH);
    }

    // ========== 生成授權 URL ==========

    @Nested
    @DisplayName("GenerateLineAuthUrl — 生成 LINE 授權 URL")
    class GenerateLineAuthUrlTests {

        @Test
        @DisplayName("成功產生授權 URL 並儲存 state")
        void generateLineAuthUrl_success() {
            String expectedUrl = "https://access.line.me/oauth2/v2.1/authorize?state=random-state";
            when(lineOAuthClient.buildAuthorizationUrl(anyString())).thenReturn(expectedUrl);
            when(stateRepository.save(any(OAuthState.class))).thenAnswer(inv -> inv.getArgument(0));

            String result = oAuthService.generateLineAuthUrl();

            assertThat(result).isEqualTo(expectedUrl);

            ArgumentCaptor<OAuthState> stateCaptor = ArgumentCaptor.forClass(OAuthState.class);
            verify(stateRepository).save(stateCaptor.capture());
            OAuthState savedState = stateCaptor.getValue();

            assertThat(savedState.getState()).isNotNull().isNotBlank();
            assertThat(savedState.getProvider()).isEqualTo(OAuthProviderType.LINE);
            assertThat(savedState.getExpiresAt()).isAfter(LocalDateTime.now(AppConstants.ZONE_ID));
        }
    }

    // ========== 驗證並消費 State ==========

    @Nested
    @DisplayName("ValidateAndConsumeState — state CSRF 驗證")
    class ValidateAndConsumeStateTests {

        @Test
        @DisplayName("有效 state → 驗證通過並刪除")
        void validState_consumedAndDeleted() {
            OAuthState validState = OAuthState.builder()
                    .state("test-state")
                    .provider(OAuthProviderType.LINE)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();
            when(stateRepository.findById("test-state")).thenReturn(Optional.of(validState));

            assertThatCode(() -> oAuthService.validateAndConsumeState("test-state"))
                    .doesNotThrowAnyException();

            verify(stateRepository).delete(validState);
        }

        @Test
        @DisplayName("state 不存在 → 拋出 IllegalArgumentException")
        void stateNotFound_throwsException() {
            when(stateRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> oAuthService.validateAndConsumeState("nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無效的 OAuth state");
        }

        @Test
        @DisplayName("state 已過期 → 刪除 state 並拋出 IllegalArgumentException")
        void expiredState_deletedAndThrowsException() {
            OAuthState expiredState = OAuthState.builder()
                    .state("expired-state")
                    .provider(OAuthProviderType.LINE)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).minusMinutes(5))
                    .build();
            when(stateRepository.findById("expired-state")).thenReturn(Optional.of(expiredState));

            assertThatThrownBy(() -> oAuthService.validateAndConsumeState("expired-state"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已過期");

            verify(stateRepository).delete(expiredState);
        }

        @Test
        @DisplayName("state provider 不符 → 拋出 IllegalArgumentException")
        void providerMismatch_throwsException() {
            OAuthState googleState = OAuthState.builder()
                    .state("google-state")
                    .provider(OAuthProviderType.GOOGLE)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();
            when(stateRepository.findById("google-state")).thenReturn(Optional.of(googleState));

            assertThatThrownBy(() -> oAuthService.validateAndConsumeState("google-state"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("provider 不符");
        }
    }

    // ========== HandleLineCallback ==========

    @Nested
    @DisplayName("HandleLineCallback — LINE callback 處理")
    class HandleLineCallbackTests {

        @BeforeEach
        void setUpCallback() {
            // 所有 callback 測試共用的 state 驗證 mock
            OAuthState validState = OAuthState.builder()
                    .state("callback-state")
                    .provider(OAuthProviderType.LINE)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();
            when(stateRepository.findById("callback-state")).thenReturn(Optional.of(validState));

            // LINE API mock
            LineTokenResponse tokenResponse = createDefaultTokenResponse();
            when(lineOAuthClient.exchangeCode("auth-code")).thenReturn(tokenResponse);

            LineProfile profile = createLineProfile(LINE_USER_ID, DISPLAY_NAME);
            when(lineOAuthClient.getProfile(ACCESS_TOKEN)).thenReturn(profile);
            when(lineOAuthClient.extractEmailFromIdToken(ID_TOKEN)).thenReturn(null);

            setupDefaultEncryption();
        }

        @Test
        @DisplayName("成功 callback — 舊用戶（路徑 1）→ 回傳 ticket")
        void existingOAuthUser_returnsTicket() {
            // 路徑 1: 已有 OAuth 綁定
            UserOAuthProvider existingProvider = createExistingOAuthProvider(USER_ID, LINE_USER_ID);
            when(oauthProviderRepository.findByProviderAndProviderUserId(OAuthProviderType.LINE, LINE_USER_ID))
                    .thenReturn(Optional.of(existingProvider));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    createExistingUser(USER_ID, "test@example.com", true, true)));
            // ensureLineBinding: 已有 binding
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            String ticket = oAuthService.handleLineCallback("auth-code", "callback-state");

            assertThat(ticket).isNotNull().isNotBlank();
            verify(stateRepository).delete(any(OAuthState.class));
            verify(lineOAuthClient).exchangeCode("auth-code");
            verify(lineOAuthClient).getProfile(ACCESS_TOKEN);
        }

        @Test
        @DisplayName("成功 callback — 新用戶（路徑 4）→ 回傳 ticket")
        void newUser_returnsTicket() {
            // 路徑 4: 新用戶
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            String ticket = oAuthService.handleLineCallback("auth-code", "callback-state");

            assertThat(ticket).isNotNull().isNotBlank();
            verify(userRepository).save(any(User.class));
        }
    }

    // ========== ResolveAndBindUser ==========

    @Nested
    @DisplayName("ResolveAndBindUser — 帳號解析四路徑")
    class ResolveAndBindUserTests {

        private LineTokenResponse tokenResponse;

        @BeforeEach
        void setUpResolve() {
            tokenResponse = createDefaultTokenResponse();
            setupDefaultEncryption();
        }

        // ----- 路徑 1: 已有 OAuth 綁定 -----

        @Test
        @DisplayName("路徑 1: 已有 OAuth 綁定 → 更新 token（加密）")
        void path1_existingOAuth_updatesEncryptedTokens() {
            UserOAuthProvider existingProvider = createExistingOAuthProvider(USER_ID, LINE_USER_ID);
            when(oauthProviderRepository.findByProviderAndProviderUserId(OAuthProviderType.LINE, LINE_USER_ID))
                    .thenReturn(Optional.of(existingProvider));
            User existingUser = createExistingUser(USER_ID, "test@example.com", true, true);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            // ensureLineBinding
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            User result = oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            assertThat(result.getUserId()).isEqualTo(USER_ID);

            // 驗證 token 被加密更新
            ArgumentCaptor<UserOAuthProvider> providerCaptor = ArgumentCaptor.forClass(UserOAuthProvider.class);
            verify(oauthProviderRepository).save(providerCaptor.capture());
            UserOAuthProvider savedProvider = providerCaptor.getValue();
            assertThat(savedProvider.getAccessToken()).isEqualTo(ENCRYPTED_ACCESS);
            assertThat(savedProvider.getRefreshToken()).isEqualTo(ENCRYPTED_REFRESH);
            assertThat(savedProvider.getDisplayName()).isEqualTo(DISPLAY_NAME);
        }

        // ----- 路徑 2: 已有 LINE Binding -----

        @Test
        @DisplayName("路徑 2: 已有 LINE Binding → 補建 OAuth 記錄")
        void path2_existingLineBinding_createsOAuthProvider() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(OAuthProviderType.LINE, LINE_USER_ID))
                    .thenReturn(Optional.empty());
            UserLineBinding existingBinding = UserLineBinding.builder()
                    .userId(USER_ID).lineUserId(LINE_USER_ID).displayName(DISPLAY_NAME).enabled(true).build();
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(existingBinding));
            User existingUser = createExistingUser(USER_ID, "test@example.com", true, true);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            assertThat(result.getUserId()).isEqualTo(USER_ID);

            // 驗證建立了 OAuth Provider
            ArgumentCaptor<UserOAuthProvider> captor = ArgumentCaptor.forClass(UserOAuthProvider.class);
            verify(oauthProviderRepository).save(captor.capture());
            UserOAuthProvider created = captor.getValue();
            assertThat(created.getUserId()).isEqualTo(USER_ID);
            assertThat(created.getProvider()).isEqualTo(OAuthProviderType.LINE);
            assertThat(created.getProviderUserId()).isEqualTo(LINE_USER_ID);
        }

        // ----- 路徑 3: email 比對 -----

        @Test
        @DisplayName("路徑 3: email 比對成功且 emailVerified=true → 自動合併")
        void path3_emailMatch_verified_autoMerge() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());

            User existingUser = createExistingUser(USER_ID, "test@example.com", true, true);
            when(userRepository.findByEmailIgnoreCase("test@example.com")).thenReturn(Optional.of(existingUser));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(
                    LINE_USER_ID, DISPLAY_NAME, "test@example.com", tokenResponse);

            assertThat(result.getUserId()).isEqualTo(USER_ID);
            // 沒有建新用戶
            verify(userRepository, never()).save(any(User.class));
            // 建立了 OAuth Provider
            verify(oauthProviderRepository).save(any(UserOAuthProvider.class));
        }

        @Test
        @DisplayName("路徑 3: email 比對到但 emailVerified=false → 不合併，建新帳號")
        void path3_emailMatch_notVerified_createsNewUser() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());

            User unverifiedUser = createExistingUser("other-user", "test@example.com", false, true);
            when(userRepository.findByEmailIgnoreCase("test@example.com")).thenReturn(Optional.of(unverifiedUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(
                    LINE_USER_ID, DISPLAY_NAME, "test@example.com", tokenResponse);

            // 建了新用戶（userId 不同於既有的）
            assertThat(result.getUserId()).isNotEqualTo("other-user");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("路徑 3: email normalize 生效 — LINE 回傳大寫 email 能比對到小寫帳號")
        void path3_emailNormalized_matchesLowerCase() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());

            User existingUser = createExistingUser(USER_ID, "test@email.com", true, true);
            // EmailNormalizer.normalize("Test@EMAIL.com") → "test@email.com"
            when(userRepository.findByEmailIgnoreCase("test@email.com")).thenReturn(Optional.of(existingUser));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(
                    LINE_USER_ID, DISPLAY_NAME, "Test@EMAIL.com", tokenResponse);

            assertThat(result.getUserId()).isEqualTo(USER_ID);
            // 確認使用 normalized email 查詢
            verify(userRepository).findByEmailIgnoreCase("test@email.com");
        }

        // ----- 路徑 4: 新用戶 -----

        @Test
        @DisplayName("路徑 4: 新用戶 → 建立帳號 + OAuth 記錄 + 發通知")
        void path4_newUser_createsAccountAndOAuthAndNotifies() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(
                    LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            // 驗證建立了新用戶
            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isNotNull().isNotBlank();

            // 驗證建立了 OAuth Provider
            ArgumentCaptor<UserOAuthProvider> oauthCaptor = ArgumentCaptor.forClass(UserOAuthProvider.class);
            verify(oauthProviderRepository).save(oauthCaptor.capture());
            UserOAuthProvider createdProvider = oauthCaptor.getValue();
            assertThat(createdProvider.getProvider()).isEqualTo(OAuthProviderType.LINE);
            assertThat(createdProvider.getProviderUserId()).isEqualTo(LINE_USER_ID);

            // 驗證發送了歡迎通知
            verify(notificationService).sendNotificationToUser(
                    eq(result.getUserId()), contains("歡迎"), anyString(), eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("路徑 4: 新用戶預設欄位驗證 — role=USER, enabled=true, discordNotification=false, lineNotification=true")
        void path4_newUser_defaultFieldValues() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User newUser = userCaptor.getValue();

            assertThat(newUser.getRole()).isEqualTo(User.Role.USER);
            assertThat(newUser.isEnabled()).isTrue();
            assertThat(newUser.isEmailVerified()).isFalse();
            assertThat(newUser.isAutoTradeEnabled()).isFalse();
            assertThat(newUser.isDiscordNotificationEnabled()).isFalse();
            assertThat(newUser.isLineNotificationEnabled()).isTrue();
            assertThat(newUser.getEmail()).isNull();
            assertThat(newUser.getPasswordHash()).isNull();
            assertThat(newUser.getName()).isEqualTo(DISPLAY_NAME);
        }

        @Test
        @DisplayName("路徑 4: 新用戶觸發 Admin 通知 + 歡迎訊息")
        void path4_newUser_triggersAdminAndWelcomeNotifications() {
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User result = oAuthService.resolveAndBindUser(
                    LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            // 歡迎訊息：給新用戶，包含 displayName（不發 Admin 通知）
            verify(notificationService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
            ArgumentCaptor<String> welcomeMsgCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendNotificationToUser(
                    eq(result.getUserId()), contains("歡迎"), welcomeMsgCaptor.capture(),
                    eq(NotificationService.COLOR_GREEN));
            assertThat(welcomeMsgCaptor.getValue()).contains(DISPLAY_NAME);
        }
    }

    // ========== Token 加密 ==========

    @Nested
    @DisplayName("Token 加密 — AES 加密存儲")
    class TokenEncryptionTests {

        @Test
        @DisplayName("accessToken 和 refreshToken 都會加密存儲")
        void bothTokensEncrypted() {
            LineTokenResponse tokenResponse = createDefaultTokenResponse();
            setupDefaultEncryption();

            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            // 驗證 encrypt 被呼叫
            verify(aesEncryptionUtil).encrypt(ACCESS_TOKEN);
            verify(aesEncryptionUtil).encrypt(REFRESH_TOKEN);

            // 驗證儲存的值是加密後的
            ArgumentCaptor<UserOAuthProvider> captor = ArgumentCaptor.forClass(UserOAuthProvider.class);
            verify(oauthProviderRepository).save(captor.capture());
            assertThat(captor.getValue().getAccessToken()).isEqualTo(ENCRYPTED_ACCESS);
            assertThat(captor.getValue().getRefreshToken()).isEqualTo(ENCRYPTED_REFRESH);
        }

        @Test
        @DisplayName("refreshToken 為 null 時不加密 — null-safe")
        void nullRefreshToken_notEncrypted() {
            LineTokenResponse tokenResponse = createTokenResponse(ACCESS_TOKEN, null, ID_TOKEN);
            when(aesEncryptionUtil.encrypt(ACCESS_TOKEN)).thenReturn(ENCRYPTED_ACCESS);

            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            // accessToken 有加密
            verify(aesEncryptionUtil).encrypt(ACCESS_TOKEN);
            // refreshToken 為 null，不應呼叫 encrypt(null)
            verify(aesEncryptionUtil, never()).encrypt(isNull());

            ArgumentCaptor<UserOAuthProvider> captor = ArgumentCaptor.forClass(UserOAuthProvider.class);
            verify(oauthProviderRepository).save(captor.capture());
            assertThat(captor.getValue().getAccessToken()).isEqualTo(ENCRYPTED_ACCESS);
            assertThat(captor.getValue().getRefreshToken()).isNull();
        }
    }

    // ========== CompleteLogin ==========

    @Nested
    @DisplayName("CompleteLogin — ticket 交換登入")
    class CompleteLoginTests {

        @Test
        @DisplayName("有效 ticket → 回傳 LoginResponse")
        void validTicket_returnsLoginResponse() throws Exception {
            // 透過 handleLineCallback 產生真實 ticket
            String ticket = prepareValidTicket();

            User user = createExistingUser(USER_ID, "test@example.com", true, true);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            LoginResponse expectedResponse = LoginResponse.builder()
                    .token("jwt-token").refreshToken("jwt-refresh").expiresIn(1800)
                    .userId(USER_ID).email("test@example.com").role("USER").build();
            when(authService.loginByOAuth(user)).thenReturn(expectedResponse);

            LoginResponse result = oAuthService.completeLogin(ticket);

            assertThat(result).isNotNull();
            assertThat(result.getToken()).isEqualTo("jwt-token");
            assertThat(result.getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("無效 ticket → 拋出 IllegalArgumentException")
        void invalidTicket_throwsException() {
            assertThatThrownBy(() -> oAuthService.completeLogin("nonexistent-ticket"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無效的 OAuth ticket");
        }

        @Test
        @DisplayName("過期 ticket → 拋出 IllegalArgumentException")
        void expiredTicket_throwsException() throws Exception {
            // 透過反射注入一個已過期的 ticket
            String ticket = "expired-test-ticket";
            injectTicket(ticket, USER_ID, System.currentTimeMillis() - 100_000);

            assertThatThrownBy(() -> oAuthService.completeLogin(ticket))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已過期");
        }

        /**
         * 透過反射產生真實 ticket 並注入 ticketStore
         */
        private String prepareValidTicket() throws Exception {
            String ticket = "valid-test-ticket";
            injectTicket(ticket, USER_ID, System.currentTimeMillis() + 60_000);
            return ticket;
        }

        @SuppressWarnings("unchecked")
        private void injectTicket(String ticket, String userId, long expiresAt) throws Exception {
            Field ticketStoreField = OAuthService.class.getDeclaredField("ticketStore");
            ticketStoreField.setAccessible(true);
            ConcurrentHashMap<String, Object> ticketStore =
                    (ConcurrentHashMap<String, Object>) ticketStoreField.get(oAuthService);

            // 透過反射取得 TicketInfo 內部 record
            Class<?> ticketInfoClass = null;
            for (Class<?> inner : OAuthService.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("TicketInfo")) {
                    ticketInfoClass = inner;
                    break;
                }
            }
            assertThat(ticketInfoClass).isNotNull();

            var constructor = ticketInfoClass.getDeclaredConstructor(String.class, long.class);
            constructor.setAccessible(true);
            Object ticketInfo = constructor.newInstance(userId, expiresAt);
            ticketStore.put(ticket, ticketInfo);
        }
    }

    // ========== EnsureLineBinding ==========

    @Nested
    @DisplayName("EnsureLineBinding — LINE Binding 確保建立或重啟")
    class EnsureLineBindingTests {

        private LineTokenResponse tokenResponse;

        @BeforeEach
        void setUp() {
            tokenResponse = createDefaultTokenResponse();
            setupDefaultEncryption();
        }

        @Test
        @DisplayName("新用戶 → 自動建立 LineBinding（enabled=true）")
        void newUser_createsLineBinding() {
            // 路徑 4 新用戶
            when(oauthProviderRepository.findByProviderAndProviderUserId(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(lineBindingRepository.findByLineUserId(anyString()))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            ArgumentCaptor<UserLineBinding> captor = ArgumentCaptor.forClass(UserLineBinding.class);
            verify(lineBindingRepository).save(captor.capture());
            UserLineBinding binding = captor.getValue();
            assertThat(binding.getLineUserId()).isEqualTo(LINE_USER_ID);
            assertThat(binding.getDisplayName()).isEqualTo(DISPLAY_NAME);
            assertThat(binding.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("已有 disabled LineBinding → 重新啟用")
        void existingDisabledBinding_reEnabled() {
            // 路徑 1: 已有 OAuth 綁定
            UserOAuthProvider existingProvider = createExistingOAuthProvider(USER_ID, LINE_USER_ID);
            when(oauthProviderRepository.findByProviderAndProviderUserId(OAuthProviderType.LINE, LINE_USER_ID))
                    .thenReturn(Optional.of(existingProvider));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    createExistingUser(USER_ID, null, false, true)));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // ensureLineBinding: 已有但 disabled
            UserLineBinding disabledBinding = UserLineBinding.builder()
                    .userId(USER_ID).lineUserId(LINE_USER_ID).displayName(DISPLAY_NAME).enabled(false).build();
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(disabledBinding));
            when(lineBindingRepository.save(any(UserLineBinding.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            ArgumentCaptor<UserLineBinding> captor = ArgumentCaptor.forClass(UserLineBinding.class);
            // oauthProviderRepository.save (path 1 token update) + lineBindingRepository.save (re-enable)
            verify(lineBindingRepository).save(captor.capture());
            UserLineBinding reEnabled = captor.getValue();
            assertThat(reEnabled.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("已有 enabled LineBinding → 不重複儲存")
        void existingEnabledBinding_noAdditionalSave() {
            // 路徑 1: 已有 OAuth 綁定
            UserOAuthProvider existingProvider = createExistingOAuthProvider(USER_ID, LINE_USER_ID);
            when(oauthProviderRepository.findByProviderAndProviderUserId(OAuthProviderType.LINE, LINE_USER_ID))
                    .thenReturn(Optional.of(existingProvider));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    createExistingUser(USER_ID, null, false, true)));
            when(oauthProviderRepository.save(any(UserOAuthProvider.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // ensureLineBinding: 已有且 enabled
            UserLineBinding enabledBinding = UserLineBinding.builder()
                    .userId(USER_ID).lineUserId(LINE_USER_ID).displayName(DISPLAY_NAME).enabled(true).build();
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(enabledBinding));

            oAuthService.resolveAndBindUser(LINE_USER_ID, DISPLAY_NAME, null, tokenResponse);

            // lineBindingRepository.save 不應被呼叫（已 enabled，不需更新）
            verify(lineBindingRepository, never()).save(any(UserLineBinding.class));
        }
    }

    // ========== CleanupExpiredStates ==========

    @Nested
    @DisplayName("CleanupExpiredStates — 清理過期 state 和 ticket")
    class CleanupExpiredStatesTests {

        @Test
        @DisplayName("清理過期 state + ticket")
        void cleansExpiredStatesAndTickets() throws Exception {
            when(stateRepository.deleteExpired(any(LocalDateTime.class))).thenReturn(3);

            // 注入一個已過期的 ticket
            injectExpiredTicket();

            oAuthService.cleanupExpiredStates();

            verify(stateRepository).deleteExpired(any(LocalDateTime.class));

            // 過期 ticket 應被清理
            assertThatThrownBy(() -> oAuthService.completeLogin("expired-cleanup-ticket"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無效");
        }

        @SuppressWarnings("unchecked")
        private void injectExpiredTicket() throws Exception {
            Field ticketStoreField = OAuthService.class.getDeclaredField("ticketStore");
            ticketStoreField.setAccessible(true);
            ConcurrentHashMap<String, Object> ticketStore =
                    (ConcurrentHashMap<String, Object>) ticketStoreField.get(oAuthService);

            Class<?> ticketInfoClass = null;
            for (Class<?> inner : OAuthService.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("TicketInfo")) {
                    ticketInfoClass = inner;
                    break;
                }
            }
            assertThat(ticketInfoClass).isNotNull();

            var constructor = ticketInfoClass.getDeclaredConstructor(String.class, long.class);
            constructor.setAccessible(true);
            // 過期 ticket（過去時間）
            Object expiredTicket = constructor.newInstance("user-expired", System.currentTimeMillis() - 100_000);
            ticketStore.put("expired-cleanup-ticket", expiredTicket);
        }
    }
}
