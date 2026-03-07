package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdvisorScheduler 單元測試
 *
 * 覆蓋：排程啟停、手動觸發、例外處理、多用戶模式
 */
class AdvisorSchedulerTest {

    private AdvisorService advisorService;
    private AdvisorConfig advisorConfig;
    private MultiUserConfig multiUserConfig;
    private UserRepository userRepository;
    private UserApiKeyService userApiKeyService;
    private ExchangeAdapterFactory exchangeAdapterFactory;
    private AdvisorScheduler scheduler;

    @BeforeEach
    void setUp() {
        advisorService = mock(AdvisorService.class);
        advisorConfig = mock(AdvisorConfig.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false
        userRepository = mock(UserRepository.class);
        userApiKeyService = mock(UserApiKeyService.class);
        exchangeAdapterFactory = mock(ExchangeAdapterFactory.class);
        scheduler = new AdvisorScheduler(advisorService, advisorConfig,
                multiUserConfig, userRepository, userApiKeyService,
                exchangeAdapterFactory);
    }

    @AfterEach
    void tearDown() {
        TradeRecordService.clearCurrentUserId();
    }

    @Nested
    @DisplayName("scheduledAdvisory — 單人模式排程觸發")
    class ScheduledSingleUserTests {

        @Test
        @DisplayName("enabled — 執行 runAdvisory")
        void enabledRunsAdvisory() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            scheduler.scheduledAdvisory();

            verify(advisorService).runAdvisory();
        }

        @Test
        @DisplayName("disabled — 跳過不執行")
        void disabledSkips() {
            when(advisorConfig.isEnabled()).thenReturn(false);

            scheduler.scheduledAdvisory();

            verify(advisorService, never()).runAdvisory();
        }

        @Test
        @DisplayName("runAdvisory 拋例外 — 不向外拋出")
        void exceptionCaught() {
            when(advisorConfig.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("Gemini API error")).when(advisorService).runAdvisory();

            assertThatCode(() -> scheduler.scheduledAdvisory()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("scheduledAdvisory — 多用戶模式")
    class ScheduledMultiUserTests {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("遍歷所有 enabled + 有 API Key 的用戶")
        void iteratesEnabledUsersWithApiKey() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            User userA = createUser("user-a", true);
            User userB = createUser("user-b", true);
            User userC = createUser("user-c", false); // disabled

            when(userRepository.findAll()).thenReturn(List.of(userA, userB, userC));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-b"))
                    .thenReturn(Optional.of(Map.entry("BYBIT", new ExchangeKeys("key-b", "secret-b"))));

            ExchangeAdapter adapterA = mock(ExchangeAdapter.class);
            ExchangeAdapter adapterB = mock(ExchangeAdapter.class);
            when(exchangeAdapterFactory.getAdapter("BINANCE")).thenReturn(adapterA);
            when(exchangeAdapterFactory.getAdapter("BYBIT")).thenReturn(adapterB);

            scheduler.scheduledAdvisory();

            // 2 個有效用戶，執行 2 次
            verify(advisorService, times(2)).runAdvisory();
            verify(advisorService, times(2)).setAdvisoryContext(any(ExchangeAdapter.class));
            verify(advisorService, times(2)).clearAdvisoryContext();
            verify(adapterA).setCredentials(new ExchangeCredentials("key-a", "secret-a"));
            verify(adapterB).setCredentials(new ExchangeCredentials("key-b", "secret-b"));
        }

        @Test
        @DisplayName("用戶無 API Key — 跳過")
        void skipsUsersWithoutApiKey() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            User user = createUser("user-no-key", true);
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-no-key"))
                    .thenReturn(Optional.empty());

            scheduler.scheduledAdvisory();

            verify(advisorService, never()).runAdvisory();
        }

        @Test
        @DisplayName("一個用戶失敗 — 不影響其他用戶")
        void oneUserFailureDoesNotAffectOthers() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            User userA = createUser("user-a", true);
            User userB = createUser("user-b", true);

            when(userRepository.findAll()).thenReturn(List.of(userA, userB));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-b"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-b", "secret-b"))));

            ExchangeAdapter adapter = mock(ExchangeAdapter.class);
            when(exchangeAdapterFactory.getAdapter("BINANCE")).thenReturn(adapter);

            // 第一次呼叫拋例外，第二次成功
            doThrow(new RuntimeException("Gemini fail"))
                    .doNothing()
                    .when(advisorService).runAdvisory();

            assertThatCode(() -> scheduler.scheduledAdvisory()).doesNotThrowAnyException();
            verify(advisorService, times(2)).runAdvisory();
        }

        @Test
        @DisplayName("finally 一定清除 ThreadLocal — 即使拋例外")
        void alwaysClearsThreadLocal() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            User user = createUser("user-a", true);
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(userApiKeyService.getUserPrimaryExchangeKeys("user-a"))
                    .thenReturn(Optional.of(Map.entry("BINANCE", new ExchangeKeys("key-a", "secret-a"))));

            ExchangeAdapter adapter = mock(ExchangeAdapter.class);
            when(exchangeAdapterFactory.getAdapter("BINANCE")).thenReturn(adapter);
            doThrow(new RuntimeException("fail")).when(advisorService).runAdvisory();

            scheduler.scheduledAdvisory();

            // 驗證 finally 有清除
            verify(advisorService).clearAdvisoryContext();
            assertThat(TradeRecordService.getCurrentUserId()).isNull();
        }
    }

    @Nested
    @DisplayName("triggerManually — 手動觸發 API")
    class ManualTriggerTests {

        @Test
        @DisplayName("enabled + 成功 — 回傳 success")
        void enabledSuccess() {
            when(advisorConfig.isEnabled()).thenReturn(true);

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "success");
            verify(advisorService).runAdvisory();
        }

        @Test
        @DisplayName("disabled — 回傳 disabled 狀態")
        void disabledReturnsDisabled() {
            when(advisorConfig.isEnabled()).thenReturn(false);

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "disabled");
            verify(advisorService, never()).runAdvisory();
        }

        @Test
        @DisplayName("runAdvisory 拋例外 — 回傳 500 error")
        void exceptionReturnsError() {
            when(advisorConfig.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("DB down")).when(advisorService).runAdvisory();

            ResponseEntity<Map<String, String>> response = scheduler.triggerManually();

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).containsEntry("status", "error");
            assertThat(response.getBody().get("message")).contains("DB down");
        }
    }

    // ========== helper ==========

    private User createUser(String userId, boolean enabled) {
        return User.builder()
                .userId(userId)
                .email(userId + "@test.com")
                .enabled(enabled)
                .autoTradeEnabled(true)
                .build();
    }
}
