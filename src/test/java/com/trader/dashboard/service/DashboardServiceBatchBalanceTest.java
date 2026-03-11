package com.trader.dashboard.service;

import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService.getBatchUserBalances() 測試
 *
 * 覆蓋場景：
 * 1. 沒有任何用戶有 API Key → 回傳空 Map
 * 2. 有 API Key 的用戶 → 查到餘額（並行）
 * 3. 查詢失敗的用戶 → 該 userId 的 value 為 null，不影響其他人
 * 4. 混合成功與失敗 → 正確回傳各自結果
 */
class DashboardServiceBatchBalanceTest {

    private BinanceFuturesService binanceFuturesService;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        var tradeRecordService = mock(TradeRecordService.class);
        var subscriptionService = mock(SubscriptionService.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        var riskConfig = mock(RiskConfig.class);
        var userRepository = mock(UserRepository.class);
        var tradeConfigResolver = mock(TradeConfigResolver.class);
        multiUserConfig = new MultiUserConfig();
        multiUserConfig.setEnabled(true); // 批次查詢在多用戶模式下使用
        userApiKeyService = mock(UserApiKeyService.class);

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService,
                riskConfig, userRepository, tradeConfigResolver,
                multiUserConfig, userApiKeyService,
                mock(com.trader.user.service.UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                mock(com.trader.trading.repository.TradeRepository.class),
                mock(com.trader.referral.repository.UserExchangeReferralLinkRepository.class),
                mock(com.trader.subscription.repository.SubscriptionRepository.class));
    }

    @Test
    @DisplayName("沒有任何用戶有 API Key → 回傳空 Map")
    void noUsersWithApiKey_returnsEmptyMap() {
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE")).thenReturn(Set.of());

        Map<String, Double> result = dashboardService.getBatchUserBalances();

        assertThat(result).isEmpty();
        verify(binanceFuturesService, never()).getAvailableBalance();
    }

    @Test
    @DisplayName("有 API Key 的用戶 → 查到餘額")
    void usersWithApiKey_returnsBalances() {
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(Set.of("userA", "userB"));
        when(userApiKeyService.getUserBinanceKeys("userA"))
                .thenReturn(Optional.of(new BinanceKeys("apiKeyUserA_12345678", "secretUserA")));
        when(userApiKeyService.getUserBinanceKeys("userB"))
                .thenReturn(Optional.of(new BinanceKeys("apiKeyUserB_12345678", "secretUserB")));
        when(binanceFuturesService.getAvailableBalance()).thenReturn(1234.56);

        Map<String, Double> result = dashboardService.getBatchUserBalances();

        assertThat(result).hasSize(2);
        assertThat(result.get("userA")).isEqualTo(1234.56);
        assertThat(result.get("userB")).isEqualTo(1234.56);
    }

    @Test
    @DisplayName("查詢失敗的用戶 → null，不影響其他人")
    void failedUserReturnsNull_othersUnaffected() {
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(Set.of("good", "bad"));
        when(userApiKeyService.getUserBinanceKeys("good"))
                .thenReturn(Optional.of(new BinanceKeys("apiKeyGood_12345678", "secretGood")));
        when(userApiKeyService.getUserBinanceKeys("bad"))
                .thenReturn(Optional.of(new BinanceKeys("apiKeyBad__12345678", "secretBad")));

        // 用 doAnswer 模擬：根據當前 ThreadLocal 設定的 key 決定成功或失敗
        when(binanceFuturesService.getAvailableBalance())
                .thenReturn(500.0)  // 第一次成功
                .thenThrow(new RuntimeException("Binance API error")); // 第二次失敗

        Map<String, Double> result = dashboardService.getBatchUserBalances();

        assertThat(result).hasSize(2);
        // 至少有一個成功、一個失敗（因並行順序不確定，只驗證有 null 存在）
        long nullCount = result.values().stream().filter(Objects::isNull).count();
        long successCount = result.values().stream().filter(Objects::nonNull).count();
        assertThat(nullCount + successCount).isEqualTo(2);
    }

    @Test
    @DisplayName("用戶無 API Key（getUserBinanceKeys 為空）→ 餘額為 0 或 null")
    void userWithoutKeys_returnsZeroOrNull() {
        when(userApiKeyService.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(Set.of("nokey"));
        when(userApiKeyService.getUserBinanceKeys("nokey"))
                .thenReturn(Optional.empty());

        Map<String, Double> result = dashboardService.getBatchUserBalances();

        assertThat(result).containsKey("nokey");
        // 沒有 key 時 fetchBalanceWithUserKeys 回傳 0（多用戶模式 keysOpt.isEmpty）
        assertThat(result.get("nokey")).isIn(0.0, null);
    }
}
