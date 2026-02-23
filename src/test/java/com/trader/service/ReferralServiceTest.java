package com.trader.service;

import com.trader.referral.config.ReferralConfig;
import com.trader.referral.dto.AdminVerifyRequest;
import com.trader.referral.dto.ReferralStatusResponse;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.entity.UserExchangeReferralLink;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.referral.service.ReferralService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReferralService 推薦系統核心服務測試
 */
class ReferralServiceTest {

    private UserExchangeReferralLinkRepository linkRepository;
    private UserRepository userRepository;
    private ReferralConfig referralConfig;
    private ReferralService service;

    @BeforeEach
    void setUp() {
        linkRepository = mock(UserExchangeReferralLinkRepository.class);
        userRepository = mock(UserRepository.class);
        referralConfig = new ReferralConfig("BINANCE", "https://binance.com/ref/test", "TEST_CODE");
        service = new ReferralService(linkRepository, userRepository, referralConfig);
    }

    // ==================== getStatus ====================

    @Nested
    @DisplayName("getStatus — 查詢推薦綁定狀態")
    class GetStatus {

        @Test
        @DisplayName("無記錄 → NOT_STARTED + 推薦連結資訊")
        void noLinkRecord() {
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());

            ReferralStatusResponse result = service.getStatus("user-1");

            assertThat(result.getStatus()).isEqualTo(ReferralStatus.NOT_STARTED);
            assertThat(result.getExchangeUid()).isNull();
            assertThat(result.getReferralLink()).isEqualTo("https://binance.com/ref/test");
            assertThat(result.getReferralCode()).isEqualTo("TEST_CODE");
        }

        @Test
        @DisplayName("有 PENDING 記錄 → 回傳 PENDING + UID")
        void pendingLink() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("12345678").status(ReferralStatus.PENDING)
                    .build();
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(link));

            ReferralStatusResponse result = service.getStatus("user-1");

            assertThat(result.getStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(result.getExchangeUid()).isEqualTo("12345678");
        }

        @Test
        @DisplayName("已 VERIFIED → 回傳 VERIFIED + verifiedAt")
        void verifiedLink() {
            var now = java.time.LocalDateTime.now();
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("12345678").status(ReferralStatus.VERIFIED)
                    .verifiedAt(now)
                    .build();
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(link));

            ReferralStatusResponse result = service.getStatus("user-1");

            assertThat(result.getStatus()).isEqualTo(ReferralStatus.VERIFIED);
            assertThat(result.getVerifiedAt()).isEqualTo(now);
        }
    }

    // ==================== submitUid ====================

    @Nested
    @DisplayName("submitUid — 提交交易所 UID")
    class SubmitUid {

        @Test
        @DisplayName("首次提交 → 建立 PENDING 記錄")
        void firstSubmit() {
            when(linkRepository.existsByExchangeAndExchangeUid("BINANCE", "99887766"))
                    .thenReturn(false);
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());
            when(linkRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ReferralStatusResponse result = service.submitUid("user-1", "99887766");

            assertThat(result.getStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(result.getExchangeUid()).isEqualTo("99887766");

            ArgumentCaptor<UserExchangeReferralLink> captor =
                    ArgumentCaptor.forClass(UserExchangeReferralLink.class);
            verify(linkRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
            assertThat(captor.getValue().getStatus()).isEqualTo(ReferralStatus.PENDING);
        }

        @Test
        @DisplayName("UID 已被其他用戶佔用 → IllegalArgumentException")
        void uidAlreadyTaken() {
            when(linkRepository.existsByExchangeAndExchangeUid("BINANCE", "taken-uid"))
                    .thenReturn(true);
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitUid("user-1", "taken-uid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已被其他帳號綁定");
        }

        @Test
        @DisplayName("自己重複提交同一 UID → 允許，更新為 PENDING")
        void resubmitSameUid() {
            UserExchangeReferralLink existing = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("my-uid").status(ReferralStatus.NOT_STARTED)
                    .build();
            when(linkRepository.existsByExchangeAndExchangeUid("BINANCE", "my-uid"))
                    .thenReturn(true);
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(existing));
            when(linkRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ReferralStatusResponse result = service.submitUid("user-1", "my-uid");

            assertThat(result.getStatus()).isEqualTo(ReferralStatus.PENDING);
        }

        @Test
        @DisplayName("已 VERIFIED 不可重複提交 → IllegalStateException")
        void cannotResubmitVerified() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("uid-123").status(ReferralStatus.VERIFIED)
                    .build();
            when(linkRepository.existsByExchangeAndExchangeUid(anyString(), anyString()))
                    .thenReturn(true);
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(link));

            assertThatThrownBy(() -> service.submitUid("user-1", "uid-123"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已通過驗證");
        }

        @Test
        @DisplayName("UID 前後空白被 trim")
        void uidTrimmed() {
            when(linkRepository.existsByExchangeAndExchangeUid("BINANCE", "12345"))
                    .thenReturn(false);
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.empty());
            when(linkRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.submitUid("user-1", "  12345  ");

            ArgumentCaptor<UserExchangeReferralLink> captor =
                    ArgumentCaptor.forClass(UserExchangeReferralLink.class);
            verify(linkRepository).save(captor.capture());
            assertThat(captor.getValue().getExchangeUid()).isEqualTo("12345");
        }
    }

    // ==================== adminVerify ====================

    @Nested
    @DisplayName("adminVerify — 管理員驗證")
    class AdminVerify {

        @Test
        @DisplayName("approve → VERIFIED + verifiedAt 設定")
        void approveVerification() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("uid-123").status(ReferralStatus.PENDING)
                    .build();
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(link));

            service.adminVerify("admin-1",
                    new AdminVerifyRequest("user-1", true, "確認推薦關係"));

            assertThat(link.getStatus()).isEqualTo(ReferralStatus.VERIFIED);
            assertThat(link.getVerifiedAt()).isNotNull();
            assertThat(link.getAdminNotes()).isEqualTo("確認推薦關係");
            verify(linkRepository).save(link);
        }

        @Test
        @DisplayName("reject → NOT_STARTED + 清空 UID")
        void rejectVerification() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("uid-123").status(ReferralStatus.PENDING)
                    .build();
            when(linkRepository.findByUserIdAndExchange("user-1", "BINANCE"))
                    .thenReturn(Optional.of(link));

            service.adminVerify("admin-1",
                    new AdminVerifyRequest("user-1", false, "UID 不在推薦名單"));

            assertThat(link.getStatus()).isEqualTo(ReferralStatus.NOT_STARTED);
            assertThat(link.getExchangeUid()).isNull();
            assertThat(link.getVerifiedAt()).isNull();
            assertThat(link.getAdminNotes()).isEqualTo("UID 不在推薦名單");
            verify(linkRepository).save(link);
        }

        @Test
        @DisplayName("找不到記錄 → IllegalArgumentException")
        void userNotFound() {
            when(linkRepository.findByUserIdAndExchange("unknown", "BINANCE"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.adminVerify("admin-1",
                    new AdminVerifyRequest("unknown", true, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("找不到用戶");
        }
    }

    // ==================== isVerified ====================

    @Nested
    @DisplayName("isVerified — 驗證狀態判斷")
    class IsVerified {

        @Test
        @DisplayName("已驗證 → true")
        void verified() {
            when(linkRepository.existsByUserIdAndExchangeAndStatus(
                    "user-1", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(true);

            assertThat(service.isVerified("user-1")).isTrue();
        }

        @Test
        @DisplayName("未驗證 → false")
        void notVerified() {
            when(linkRepository.existsByUserIdAndExchangeAndStatus(
                    "user-1", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(false);

            assertThat(service.isVerified("user-1")).isFalse();
        }
    }

    // ==================== getPendingList ====================

    @Nested
    @DisplayName("getPendingList — 待驗證列表")
    class GetPendingList {

        @Test
        @DisplayName("有待驗證記錄 → 回傳含 email 的列表")
        void pendingListWithEmail() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1").exchange("BINANCE")
                    .exchangeUid("uid-456").status(ReferralStatus.PENDING)
                    .build();
            when(linkRepository.findByStatus(ReferralStatus.PENDING))
                    .thenReturn(List.of(link));
            when(userRepository.findById("user-1"))
                    .thenReturn(Optional.of(User.builder()
                            .userId("user-1").email("test@example.com")
                            .passwordHash("hash").build()));

            var result = service.getPendingList();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo("user-1");
            assertThat(result.get(0).getEmail()).isEqualTo("test@example.com");
            assertThat(result.get(0).getExchangeUid()).isEqualTo("uid-456");
        }
    }
}
