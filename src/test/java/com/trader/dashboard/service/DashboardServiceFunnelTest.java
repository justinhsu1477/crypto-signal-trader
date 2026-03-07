package com.trader.dashboard.service;

import com.trader.dashboard.dto.FunnelStatsResponse;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserDiscordWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DashboardService.getFunnelStats() 測試
 *
 * 重點測試：
 * - 6 階段漏斗計數正確
 * - 用戶 stage 判斷邏輯
 * - 註冊趨勢資料
 * - 最近註冊列表
 */
class DashboardServiceFunnelTest {

    private UserRepository userRepository;
    private TradeRepository tradeRepository;
    private UserExchangeReferralLinkRepository referralLinkRepository;
    private SubscriptionRepository subscriptionRepository;
    private UserApiKeyService userApiKeyService;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tradeRepository = mock(TradeRepository.class);
        referralLinkRepository = mock(UserExchangeReferralLinkRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        userApiKeyService = mock(UserApiKeyService.class);

        dashboardService = new DashboardService(
                mock(TradeRecordService.class),
                mock(SubscriptionService.class),
                mock(ExchangeAdapterFactory.class),
                mock(RiskConfig.class),
                userRepository,
                mock(TradeConfigResolver.class),
                mock(MultiUserConfig.class),
                userApiKeyService,
                mock(UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                tradeRepository,
                referralLinkRepository,
                subscriptionRepository
        );
    }

    private User createUser(String userId, String name, String email, boolean emailVerified) {
        User user = new User();
        user.setUserId(userId);
        user.setName(name);
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    @Nested
    @DisplayName("漏斗計數")
    class FunnelCounts {

        @Test
        @DisplayName("全部為 0 時返回正確結構")
        void allZero() {
            when(userRepository.count()).thenReturn(0L);
            when(userRepository.countByEmailVerifiedTrue()).thenReturn(0L);
            when(referralLinkRepository.countByStatus(ReferralStatus.VERIFIED)).thenReturn(0L);
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of());
            when(tradeRepository.countDistinctUserIdsWithClosedTrades()).thenReturn(0L);
            when(subscriptionRepository.countActiveSubscriptions()).thenReturn(0L);
            when(userRepository.countRegistrationsByDate(any())).thenReturn(List.of());
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getTotalUsers()).isZero();
            assertThat(result.getEmailVerified()).isZero();
            assertThat(result.getReferralVerified()).isZero();
            assertThat(result.getHasApiKey()).isZero();
            assertThat(result.getHasTraded()).isZero();
            assertThat(result.getActiveSubscription()).isZero();
            assertThat(result.getRegistrationsByDate()).isEmpty();
            assertThat(result.getRecentUsers()).isEmpty();
        }

        @Test
        @DisplayName("正常數據返回正確計數")
        void normalCounts() {
            when(userRepository.count()).thenReturn(100L);
            when(userRepository.countByEmailVerifiedTrue()).thenReturn(80L);
            when(referralLinkRepository.countByStatus(ReferralStatus.VERIFIED)).thenReturn(50L);
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u1", "u2", "u3"));
            when(tradeRepository.countDistinctUserIdsWithClosedTrades()).thenReturn(2L);
            when(subscriptionRepository.countActiveSubscriptions()).thenReturn(1L);
            when(userRepository.countRegistrationsByDate(any())).thenReturn(List.of());
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getTotalUsers()).isEqualTo(100);
            assertThat(result.getEmailVerified()).isEqualTo(80);
            assertThat(result.getReferralVerified()).isEqualTo(50);
            assertThat(result.getHasApiKey()).isEqualTo(3);
            assertThat(result.getHasTraded()).isEqualTo(2);
            assertThat(result.getActiveSubscription()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("用戶 stage 判斷")
    class UserStageDetermination {

        @Test
        @DisplayName("已訂閱用戶 → subscribed")
        void subscribedUser() {
            User user = createUser("u1", "Alice", "alice@test.com", true);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of("u1"));
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("subscribed");
        }

        @Test
        @DisplayName("有交易紀錄用戶 → traded")
        void tradedUser() {
            User user = createUser("u2", "Bob", "bob@test.com", true);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u2", "CLOSED")).thenReturn(5L);
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("traded");
        }

        @Test
        @DisplayName("有 API Key 用戶 → api_key_set")
        void apiKeyUser() {
            User user = createUser("u3", "Charlie", "charlie@test.com", true);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u3", "CLOSED")).thenReturn(0L);
            when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of("u3"));
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("api_key_set");
        }

        @Test
        @DisplayName("推薦碼已驗證 → referral_verified")
        void referralVerifiedUser() {
            User user = createUser("u4", "Diana", "diana@test.com", true);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u4", "CLOSED")).thenReturn(0L);
            when(referralLinkRepository.existsByUserIdAndExchangeAndStatus("u4", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(true);
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("referral_verified");
        }

        @Test
        @DisplayName("Email 已驗證 → email_verified")
        void emailVerifiedUser() {
            User user = createUser("u5", "Eve", "eve@test.com", true);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u5", "CLOSED")).thenReturn(0L);
            when(referralLinkRepository.existsByUserIdAndExchangeAndStatus("u5", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(false);
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("email_verified");
        }

        @Test
        @DisplayName("只有註冊 → registered")
        void registeredOnlyUser() {
            User user = createUser("u6", "Frank", null, false);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u6", "CLOSED")).thenReturn(0L);
            when(referralLinkRepository.existsByUserIdAndExchangeAndStatus("u6", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(false);
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(user));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            assertThat(result.getRecentUsers().get(0).getStage()).isEqualTo("registered");
        }
    }

    @Nested
    @DisplayName("註冊趨勢")
    class RegistrationTrend {

        @Test
        @DisplayName("返回正確的日期分組數據")
        void registrationsByDate() {
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());

            Object[] row1 = new Object[]{java.sql.Date.valueOf("2026-03-01"), 5L};
            Object[] row2 = new Object[]{java.sql.Date.valueOf("2026-03-02"), 3L};
            when(userRepository.countRegistrationsByDate(any())).thenReturn(List.of(row1, row2));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRegistrationsByDate()).hasSize(2);
            assertThat(result.getRegistrationsByDate().get(0).getDate()).isEqualTo("2026-03-01");
            assertThat(result.getRegistrationsByDate().get(0).getCount()).isEqualTo(5);
            assertThat(result.getRegistrationsByDate().get(1).getCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("最近註冊列表")
    class RecentUsersList {

        @Test
        @DisplayName("LINE 用戶 email 為 null")
        void lineUserNullEmail() {
            User lineUser = createUser("u-line", "LINE User", null, false);
            setupBasicMocks();
            when(subscriptionRepository.findUserIdsWithActiveSubscription()).thenReturn(List.of());
            when(tradeRepository.countByUserIdAndStatus("u-line", "CLOSED")).thenReturn(0L);
            when(referralLinkRepository.existsByUserIdAndExchangeAndStatus("u-line", "BINANCE", ReferralStatus.VERIFIED))
                    .thenReturn(false);
            when(userRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(lineUser));

            FunnelStatsResponse result = dashboardService.getFunnelStats();

            assertThat(result.getRecentUsers()).hasSize(1);
            FunnelStatsResponse.RecentUser recent = result.getRecentUsers().get(0);
            assertThat(recent.getName()).isEqualTo("LINE User");
            assertThat(recent.getEmail()).isNull();
            assertThat(recent.getCreatedAt()).isNotNull();
        }
    }

    private void setupBasicMocks() {
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByEmailVerifiedTrue()).thenReturn(8L);
        when(referralLinkRepository.countByStatus(ReferralStatus.VERIFIED)).thenReturn(5L);
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of());
        when(tradeRepository.countDistinctUserIdsWithClosedTrades()).thenReturn(2L);
        when(subscriptionRepository.countActiveSubscriptions()).thenReturn(1L);
        when(userRepository.countRegistrationsByDate(any())).thenReturn(List.of());
    }
}
