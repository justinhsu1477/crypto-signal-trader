package com.trader.chatbot.service;

import com.trader.chatbot.service.IntentClassifier.Intent;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.repository.UserTradeSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@DisplayName("UserContextGatherer — 上下文收集")
class UserContextGathererTest {

    @Mock private UserRepository userRepository;
    @Mock private TradeRepository tradeRepository;
    @Mock private UserTradeSettingsRepository userTradeSettingsRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BroadcastLogRepository broadcastLogRepository;
    @Mock private MarketDataService marketDataService;

    private UserContextGatherer gatherer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gatherer = new UserContextGatherer(userRepository, tradeRepository,
                userTradeSettingsRepository, subscriptionRepository, broadcastLogRepository,
                marketDataService);
    }

    @Test
    @DisplayName("ACCOUNT_STATUS — 包含帳號和訂閱資訊")
    void accountStatus() {
        User user = User.builder().email("test@test.com").name("TestUser").build();
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        Subscription sub = Subscription.builder().planId("pro").status(Subscription.Status.ACTIVE)
                .currentPeriodEnd(LocalDateTime.of(2026, 4, 1, 0, 0)).build();
        when(subscriptionRepository.findActiveByUserId("u1")).thenReturn(Optional.of(sub));
        when(userTradeSettingsRepository.findById("u1")).thenReturn(Optional.empty());

        String context = gatherer.gatherContext("u1", Intent.ACCOUNT_STATUS);

        assertThat(context).contains("TestUser");
        assertThat(context).contains("pro");
        assertThat(context).contains("ACTIVE");
    }

    @Test
    @DisplayName("TRADE_QUERY — 包含交易紀錄和統計")
    void tradeQuery() {
        Trade trade = Trade.builder()
                .symbol("BTCUSDT").side("LONG").netProfit(100.0)
                .exitTime(LocalDateTime.of(2026, 3, 14, 10, 0)).build();
        when(tradeRepository.findUserClosedTradesAfter(eq("u1"), any()))
                .thenReturn(List.of(trade));
        when(tradeRepository.countUserClosedTrades("u1")).thenReturn(50L);
        when(tradeRepository.countUserWinningTrades("u1")).thenReturn(30L);
        when(tradeRepository.sumUserNetProfit("u1")).thenReturn(1500.0);

        String context = gatherer.gatherContext("u1", Intent.TRADE_QUERY);

        assertThat(context).contains("BTCUSDT");
        assertThat(context).contains("100.00");
        assertThat(context).contains("60.0%"); // 30/50
        assertThat(context).contains("1500.00");
    }

    @Test
    @DisplayName("無交易紀錄 — 顯示無資料")
    void noTrades() {
        when(tradeRepository.findUserClosedTradesAfter(eq("u1"), any())).thenReturn(Collections.emptyList());
        when(tradeRepository.countUserClosedTrades("u1")).thenReturn(0L);
        when(tradeRepository.countUserWinningTrades("u1")).thenReturn(0L);
        when(tradeRepository.sumUserNetProfit("u1")).thenReturn(0.0);

        String context = gatherer.gatherContext("u1", Intent.TRADE_QUERY);

        assertThat(context).contains("無已平倉交易");
    }

    @Test
    @DisplayName("Repository 拋異常 → 不中斷，回傳部分資料")
    void repositoryError() {
        when(userRepository.findById("u1")).thenThrow(new RuntimeException("DB error"));
        when(userTradeSettingsRepository.findById("u1")).thenReturn(Optional.empty());

        String context = gatherer.gatherContext("u1", Intent.ACCOUNT_STATUS);

        assertThat(context).contains("載入失敗");
    }

    @Test
    @DisplayName("SIGNAL_EXPLAIN — 包含廣播紀錄")
    void signalExplain() {
        when(tradeRepository.findUserClosedTradesAfter(eq("u1"), any())).thenReturn(Collections.emptyList());
        when(broadcastLogRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        String context = gatherer.gatherContext("u1", Intent.SIGNAL_EXPLAIN);

        assertThat(context).contains("無近期廣播紀錄");
    }

    // ===== Admin Context Tests =====

    @Test
    @DisplayName("Admin — 精確匹配單一用戶 → 載入詳細資料")
    void adminSingleUserMatch() {
        User alice = User.builder().userId("u1").name("Alice").email("alice@test.com").role(User.Role.USER).build();
        User bob = User.builder().userId("u2").name("Bob").email("bob@test.com").role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(alice, bob));
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
        when(subscriptionRepository.findActiveByUserId("u1")).thenReturn(Optional.empty());
        when(tradeRepository.findUserClosedTradesAfter(eq("u1"), any())).thenReturn(Collections.emptyList());
        when(tradeRepository.countUserClosedTrades("u1")).thenReturn(5L);
        when(tradeRepository.countUserWinningTrades("u1")).thenReturn(3L);
        when(tradeRepository.sumUserNetProfit("u1")).thenReturn(200.0);
        when(userTradeSettingsRepository.findById("u1")).thenReturn(Optional.empty());

        String context = gatherer.gatherAdminContext("用戶 Alice 最近交易如何");

        assertThat(context).contains("Alice");
        assertThat(context).contains("詳細資料");
        assertThat(context).doesNotContain("多位用戶匹配");
    }

    @Test
    @DisplayName("Admin — 多用戶匹配（名字子字串相同）→ 列出候選名單提示指定")
    void adminMultipleUserMatch() {
        // 兩個名字都包含「小明」
        User ming1 = User.builder().userId("u1").name("蘇小明").email("su1@test.com").role(User.Role.USER).build();
        User ming2 = User.builder().userId("u2").name("王小明").email("wang1@test.com").role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(ming1, ming2));
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());

        String context = gatherer.gatherAdminContext("小明最近交易如何");

        assertThat(context).contains("多位用戶匹配");
        assertThat(context).contains("蘇小明");
        assertThat(context).contains("王小明");
    }

    @Test
    @DisplayName("Admin — 純英文短名字（1字元）不觸發比對 → 不誤匹配")
    void adminShortEnglishNameIgnored() {
        User x = User.builder().userId("u1").name("X").email("x@test.com").role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(x));
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());

        String context = gatherer.gatherAdminContext("Max 的交易狀況");

        assertThat(context).doesNotContain("詳細資料");
        assertThat(context).doesNotContain("多位用戶匹配");
    }

    @Test
    @DisplayName("Admin — 無匹配用戶 → 只有用戶列表和平台統計")
    void adminNoUserMatch() {
        User alice = User.builder().userId("u1").name("Alice").email("alice@test.com").role(User.Role.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(alice));
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());

        String context = gatherer.gatherAdminContext("平台整體狀況如何");

        assertThat(context).contains("全平台用戶列表");
        assertThat(context).contains("全平台交易統計");
        assertThat(context).doesNotContain("詳細資料");
    }

    @Test
    @DisplayName("Admin — 平台統計用批次查詢（aggregateStatsPerUser）")
    void adminPlatformStatsBatchQuery() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        Object[] stat = new Object[]{"u1", 10L, 6L, 500.0, 2L};
        List<Object[]> statList = new java.util.ArrayList<>();
        statList.add(stat);
        when(tradeRepository.aggregateStatsPerUser()).thenReturn(statList);

        String context = gatherer.gatherAdminContext("平台統計");

        assertThat(context).contains("10 筆");
        assertThat(context).contains("60.0%");
        assertThat(context).contains("500.00");
    }
}
