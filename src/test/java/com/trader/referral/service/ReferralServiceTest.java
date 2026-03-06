package com.trader.referral.service;

import com.trader.referral.config.ReferralConfig;
import com.trader.referral.dto.AdminPendingResponse;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.entity.UserExchangeReferralLink;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferralServiceTest {

    private UserExchangeReferralLinkRepository linkRepository;
    private UserRepository userRepository;
    private ReferralConfig referralConfig;
    private ReferralService referralService;

    @BeforeEach
    void setUp() {
        linkRepository = mock(UserExchangeReferralLinkRepository.class);
        userRepository = mock(UserRepository.class);
        referralConfig = mock(ReferralConfig.class);
        referralService = new ReferralService(linkRepository, userRepository, referralConfig);

        when(referralConfig.getDefaultExchange()).thenReturn("BINANCE");
        when(referralConfig.getReferralLink()).thenReturn("https://binance.com/ref");
        when(referralConfig.getReferralCode()).thenReturn("REF123");
    }

    @Nested
    @DisplayName("getPendingList")
    class GetPendingListTests {

        @Test
        @DisplayName("正常用戶 — 回傳 name 和 email")
        void shouldReturnNameAndEmail() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("user-1")
                    .exchange("BINANCE")
                    .exchangeUid("123456")
                    .status(ReferralStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

            User user = User.builder()
                    .userId("user-1")
                    .name("Test User")
                    .email("test@example.com")
                    .build();

            when(linkRepository.findByStatus(ReferralStatus.PENDING)).thenReturn(List.of(link));
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            List<AdminPendingResponse> result = referralService.getPendingList();

            assertEquals(1, result.size());
            AdminPendingResponse resp = result.get(0);
            assertEquals("user-1", resp.getUserId());
            assertEquals("Test User", resp.getName());
            assertEquals("test@example.com", resp.getEmail());
            assertEquals("123456", resp.getExchangeUid());
        }

        @Test
        @DisplayName("LINE 用戶 — email 為 null，name 有值")
        void shouldHandleLineUserWithNullEmail() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("line-user")
                    .exchange("BINANCE")
                    .exchangeUid("789012")
                    .status(ReferralStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

            User lineUser = User.builder()
                    .userId("line-user")
                    .name("LINE Display Name")
                    .email(null)  // LINE 用戶沒有 email
                    .build();

            when(linkRepository.findByStatus(ReferralStatus.PENDING)).thenReturn(List.of(link));
            when(userRepository.findById("line-user")).thenReturn(Optional.of(lineUser));

            List<AdminPendingResponse> result = referralService.getPendingList();

            assertEquals(1, result.size());
            AdminPendingResponse resp = result.get(0);
            assertEquals("LINE Display Name", resp.getName());
            assertNull(resp.getEmail());
        }

        @Test
        @DisplayName("用戶不存在 — name 回傳 unknown，email 為 null")
        void shouldHandleMissingUser() {
            UserExchangeReferralLink link = UserExchangeReferralLink.builder()
                    .userId("deleted-user")
                    .exchange("BINANCE")
                    .exchangeUid("999999")
                    .status(ReferralStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(linkRepository.findByStatus(ReferralStatus.PENDING)).thenReturn(List.of(link));
            when(userRepository.findById("deleted-user")).thenReturn(Optional.empty());

            List<AdminPendingResponse> result = referralService.getPendingList();

            assertEquals(1, result.size());
            AdminPendingResponse resp = result.get(0);
            assertEquals("unknown", resp.getName());
            assertNull(resp.getEmail());
        }

        @Test
        @DisplayName("空列表")
        void shouldReturnEmptyList() {
            when(linkRepository.findByStatus(ReferralStatus.PENDING)).thenReturn(List.of());

            List<AdminPendingResponse> result = referralService.getPendingList();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("多筆 — 混合 email 和 LINE 用戶")
        void shouldHandleMixedUsers() {
            UserExchangeReferralLink link1 = UserExchangeReferralLink.builder()
                    .userId("email-user").exchange("BINANCE").exchangeUid("111")
                    .status(ReferralStatus.PENDING).createdAt(LocalDateTime.now()).build();
            UserExchangeReferralLink link2 = UserExchangeReferralLink.builder()
                    .userId("line-user").exchange("BINANCE").exchangeUid("222")
                    .status(ReferralStatus.PENDING).createdAt(LocalDateTime.now()).build();

            User emailUser = User.builder().userId("email-user").name("Email User").email("a@b.com").build();
            User lineUser = User.builder().userId("line-user").name("LINE User").email(null).build();

            when(linkRepository.findByStatus(ReferralStatus.PENDING)).thenReturn(List.of(link1, link2));
            when(userRepository.findById("email-user")).thenReturn(Optional.of(emailUser));
            when(userRepository.findById("line-user")).thenReturn(Optional.of(lineUser));

            List<AdminPendingResponse> result = referralService.getPendingList();

            assertEquals(2, result.size());
            assertEquals("a@b.com", result.get(0).getEmail());
            assertNull(result.get(1).getEmail());
            assertEquals("LINE User", result.get(1).getName());
        }
    }
}
