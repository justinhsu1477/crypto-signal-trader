package com.trader.notification.service;

import com.trader.shared.config.LineConfig;
import com.trader.user.entity.LineLinkingCode;
import com.trader.user.entity.UserLineBinding;
import com.trader.user.repository.LineLinkingCodeRepository;
import com.trader.user.repository.UserLineBindingRepository;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import com.trader.shared.config.AppConstants;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LineLinkingService 單元測試
 *
 * 覆蓋：連結碼產生、綁定流程（有效碼/過期碼/已綁定）、follow/unfollow、解綁、
 *       Rich Menu 切換、關鍵字回覆（「客服」「綁定」）
 */
class LineLinkingServiceTest {

    private LineConfig lineConfig;
    private UserLineBindingRepository lineBindingRepository;
    private LineLinkingCodeRepository linkingCodeRepository;
    private OkHttpClient httpClient;
    private LineRichMenuService richMenuService;
    private LineLinkingService service;
    private Call mockCall;

    private static final String USER_ID = "test-user-123";
    private static final String LINE_USER_ID = "U1234567890abcdef";

    @BeforeEach
    void setUp() {
        lineConfig = mock(LineConfig.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);
        linkingCodeRepository = mock(LineLinkingCodeRepository.class);
        httpClient = mock(OkHttpClient.class);
        richMenuService = mock(LineRichMenuService.class);

        when(lineConfig.getChannelAccessToken()).thenReturn("test-token");
        when(lineConfig.getLinkingCodeExpiryMinutes()).thenReturn(10);

        mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);

        service = new LineLinkingService(lineConfig, lineBindingRepository,
                linkingCodeRepository, httpClient, richMenuService);
    }

    // ==================== generateLinkingCode ====================

    @Nested
    @DisplayName("generateLinkingCode — 產生連結碼")
    class GenerateLinkingCodeTests {

        @Test
        @DisplayName("產生 8 碼並存入 DB")
        void generatesAndSavesCode() {
            String code = service.generateLinkingCode(USER_ID);

            assertThat(code).hasSize(8);
            assertThat(code).matches("[A-Z2-9]+"); // 排除 O/0/I/1

            verify(linkingCodeRepository).deleteByUserId(USER_ID);
            ArgumentCaptor<LineLinkingCode> captor = ArgumentCaptor.forClass(LineLinkingCode.class);
            verify(linkingCodeRepository).save(captor.capture());

            LineLinkingCode saved = captor.getValue();
            assertThat(saved.getCode()).isEqualTo(code);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.isUsed()).isFalse();
        }
    }

    // ==================== handleMessage — 綁定 ====================

    @Nested
    @DisplayName("handleMessage — 連結碼綁定")
    class HandleMessageTests {

        @Test
        @DisplayName("有效碼 → 建立綁定 + 切換 Rich Menu")
        void validCodeCreatesBindingAndSwitchesMenu() {
            LineLinkingCode code = LineLinkingCode.builder()
                    .code("ABCD1234")
                    .userId(USER_ID)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .used(false)
                    .build();

            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());
            when(linkingCodeRepository.findByCodeAndUsedFalse("ABCD1234")).thenReturn(Optional.of(code));
            when(lineBindingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            service.handleMessage(LINE_USER_ID, "abcd1234", "reply-token");

            // 驗證碼被標記為已使用
            assertThat(code.isUsed()).isTrue();
            verify(linkingCodeRepository).save(code);

            // 驗證綁定被建立
            ArgumentCaptor<UserLineBinding> captor = ArgumentCaptor.forClass(UserLineBinding.class);
            verify(lineBindingRepository).save(captor.capture());

            UserLineBinding binding = captor.getValue();
            assertThat(binding.getUserId()).isEqualTo(USER_ID);
            assertThat(binding.getLineUserId()).isEqualTo(LINE_USER_ID);
            assertThat(binding.isEnabled()).isTrue();

            // 驗證 Rich Menu 切換到已綁定版
            verify(richMenuService).linkBoundMenu(LINE_USER_ID);

            // 驗證回覆成功訊息
            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("過期碼 → 拒絕綁定")
        void expiredCodeRejected() {
            LineLinkingCode code = LineLinkingCode.builder()
                    .code("EXPIRED1")
                    .userId(USER_ID)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).minusMinutes(1)) // 已過期
                    .used(false)
                    .build();

            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());
            when(linkingCodeRepository.findByCodeAndUsedFalse("EXPIRED1")).thenReturn(Optional.of(code));

            service.handleMessage(LINE_USER_ID, "EXPIRED1", "reply-token");

            // 不應建立綁定
            verify(lineBindingRepository, never()).save(any());
            verify(richMenuService, never()).linkBoundMenu(any());
            // 回覆過期訊息
            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("已綁定用戶 → 提示已綁定")
        void alreadyBoundUserNotified() {
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(
                    Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            service.handleMessage(LINE_USER_ID, "ANYCODE1", "reply-token");

            // 不應查碼或建立綁定
            verify(linkingCodeRepository, never()).findByCodeAndUsedFalse(any());
            verify(lineBindingRepository, never()).save(any());
        }

        @Test
        @DisplayName("非 8 碼文字 → 提示輸入正確碼（含關鍵字提示）")
        void shortTextShowsHint() {
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            service.handleMessage(LINE_USER_ID, "hi", "reply-token");

            verify(linkingCodeRepository, never()).findByCodeAndUsedFalse(any());
            verify(httpClient).newCall(any()); // 回覆提示
        }
    }

    // ==================== 關鍵字回覆 ====================

    @Nested
    @DisplayName("關鍵字回覆")
    class KeywordTests {

        @Test
        @DisplayName("「綁定」→ 回覆綁定指引")
        void bindKeywordShowsGuide() {
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            service.handleMessage(LINE_USER_ID, "綁定", "reply-token");

            // 不應查連結碼
            verify(linkingCodeRepository, never()).findByCodeAndUsedFalse(any());
            // 應回覆訊息
            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("「客服」→ 回覆客服資訊")
        void supportKeywordShowsInfo() {
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            service.handleMessage(LINE_USER_ID, "客服", "reply-token");

            verify(linkingCodeRepository, never()).findByCodeAndUsedFalse(any());
            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("已綁定用戶輸入「客服」→ 也能收到客服資訊")
        void boundUserCanAskSupport() {
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID)).thenReturn(
                    Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            service.handleMessage(LINE_USER_ID, "客服", "reply-token");

            // 不應提示已綁定，而是回覆客服資訊
            verify(httpClient).newCall(any());
        }
    }

    // ==================== handleFollow / handleUnfollow ====================

    @Nested
    @DisplayName("follow / unfollow 事件")
    class FollowUnfollowTests {

        @Test
        @DisplayName("follow → 回覆歡迎訊息")
        void followRepliesWelcome() {
            service.handleFollow(LINE_USER_ID, "reply-token");

            verify(httpClient).newCall(any());
            verify(mockCall).enqueue(any());
        }

        @Test
        @DisplayName("unfollow → 停用綁定")
        void unfollowDisablesBinding() {
            UserLineBinding binding = UserLineBinding.builder()
                    .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build();
            when(lineBindingRepository.findByLineUserId(LINE_USER_ID))
                    .thenReturn(Optional.of(binding));

            service.handleUnfollow(LINE_USER_ID);

            assertThat(binding.isEnabled()).isFalse();
            verify(lineBindingRepository).save(binding);
        }
    }

    // ==================== unbind ====================

    @Test
    @DisplayName("unbind → 移除 Rich Menu + 刪除綁定記錄")
    void unbindRemovesMenuAndDeletesRecord() {
        UserLineBinding binding = UserLineBinding.builder()
                .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build();
        when(lineBindingRepository.findById(USER_ID)).thenReturn(Optional.of(binding));

        service.unbind(USER_ID);

        verify(richMenuService).unlinkUserMenu(LINE_USER_ID);
        verify(lineBindingRepository).deleteById(USER_ID);
    }

    @Test
    @DisplayName("unbind 無綁定 → 不呼叫 unlinkUserMenu")
    void unbindWithoutBindingSkipsMenuUnlink() {
        when(lineBindingRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.unbind(USER_ID);

        verify(richMenuService, never()).unlinkUserMenu(any());
        verify(lineBindingRepository).deleteById(USER_ID);
    }
}
