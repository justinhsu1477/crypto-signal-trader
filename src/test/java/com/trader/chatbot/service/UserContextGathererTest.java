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

    private UserContextGatherer gatherer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gatherer = new UserContextGatherer(userRepository, tradeRepository,
                userTradeSettingsRepository, subscriptionRepository, broadcastLogRepository);
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
}
