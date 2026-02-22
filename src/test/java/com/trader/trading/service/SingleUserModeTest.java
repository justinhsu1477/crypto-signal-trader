package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 單人模式（MULTI_USER_ENABLED=false）相容性測試
 *
 * 驗證上一輪多用戶架構修復後，單人模式下的核心路徑仍能正常運行：
 * 1. userId 一致性（去重 vs 存入 DB）
 * 2. 查詢走全局路徑（不帶 userId 過濾）
 * 3. 無 ownership check（findById / findEvents）
 */
class SingleUserModeTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private TradeRecordService service;
    private MultiUserConfig multiUserConfig;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        multiUserConfig = new MultiUserConfig(); // enabled=false（預設）
        service = new TradeRecordService(tradeRepository, tradeEventRepository,
                new ObjectMapper(), multiUserConfig, "test-user");
    }

    // ==================== userId 一致性 ====================

    @Nested
    @DisplayName("userId 一致性")
    class UserIdConsistencyTests {

        @Test
        @DisplayName("ThreadLocal 為 null 時 — getActiveUserId 回傳 fallback（不是 'default'）")
        void getActiveUserId_fallbackToDefaultUserId() {
            // ThreadLocal 未設值（模擬 API Key 認證，無 JWT）
            TradeRecordService.clearCurrentUserId();

            String userId = service.getActiveUserId();

            // 應回傳建構子注入的 defaultUserId（"test-user"），不是硬編碼的 "default"
            assertThat(userId).isEqualTo("test-user");
            assertThat(userId).isNotEqualTo("default");
        }

        @Test
        @DisplayName("ThreadLocal 有值時 — getActiveUserId 回傳 ThreadLocal 值")
        void getActiveUserId_withThreadLocal() {
            TradeRecordService.setCurrentUserId("BECK_TEST");
            try {
                String userId = service.getActiveUserId();
                assertThat(userId).isEqualTo("BECK_TEST");
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }

        @Test
        @DisplayName("ThreadLocal 為空字串時 — fallback 到 defaultUserId")
        void getActiveUserId_emptyStringFallback() {
            TradeRecordService.setCurrentUserId("");
            try {
                String userId = service.getActiveUserId();
                assertThat(userId).isEqualTo("test-user"); // fallback 到建構子注入的 defaultUserId
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }

        @Test
        @DisplayName("recordEntry 存入的 userId 與 getActiveUserId 一致")
        void recordEntry_userIdConsistent() {
            TradeRecordService.clearCurrentUserId();

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(94000)
                    .build();

            OrderResult entryOrder = OrderResult.builder()
                    .success(true)
                    .orderId("12345")
                    .price(95000)
                    .quantity(0.01)
                    .commission(0.019)
                    .build();

            OrderResult slOrder = OrderResult.builder()
                    .success(true)
                    .orderId("12346")
                    .price(94000)
                    .quantity(0.01)
                    .build();

            service.recordEntry(signal, entryOrder, slOrder, 20, 100.0, "hash-abc");

            ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
            verify(tradeRepository).save(captor.capture());

            Trade saved = captor.getValue();
            // userId 應與 getActiveUserId() 回傳值一致
            assertThat(saved.getUserId()).isEqualTo(service.getActiveUserId());
            assertThat(saved.getUserId()).isNotEqualTo("default");
        }
    }

    // ==================== 查詢隔離：單人模式走全局路徑 ====================

    @Nested
    @DisplayName("查詢隔離 — 單人模式走全局路徑")
    class SingleModeQueryTests {

        @Test
        @DisplayName("cleanupStaleTrades — 走 findByStatus 全局查詢")
        void cleanupStaleTrades_globalQuery() {
            assertThat(multiUserConfig.isEnabled()).isFalse();

            Trade trade1 = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).userId("user-A").status("OPEN").build();
            Trade trade2 = Trade.builder()
                    .tradeId("t2").symbol("ETHUSDT").side("SHORT")
                    .entryPrice(3000.0).userId("user-B").status("OPEN").build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade1, trade2));

            // positionChecker 回傳 0 表示幣安無持倉
            service.cleanupStaleTrades(symbol -> 0.0);

            // 兩筆都應被清理（單人模式不分用戶）
            verify(tradeRepository, times(2)).save(any(Trade.class));
            assertThat(trade1.getStatus()).isEqualTo("CANCELLED");
            assertThat(trade2.getStatus()).isEqualTo("CANCELLED");

            // 確認呼叫的是全局查詢，不是 user-scoped
            verify(tradeRepository).findByStatus("OPEN");
            verify(tradeRepository, never()).findByUserIdAndStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("getDcaCount — 走全局計數")
        void getDcaCount_globalQuery() {
            when(tradeRepository.findDcaCountBySymbol("BTCUSDT")).thenReturn(Optional.of(2));

            int count = service.getDcaCount("BTCUSDT");

            assertThat(count).isEqualTo(2);
            verify(tradeRepository).findDcaCountBySymbol("BTCUSDT");
            verify(tradeRepository, never()).findUserDcaCountBySymbol(anyString(), anyString());
        }
    }

    // ==================== Ownership check：單人模式不驗證 ====================

    @Nested
    @DisplayName("Ownership — 單人模式不驗證")
    class NoOwnershipCheckTests {

        @Test
        @DisplayName("findById — 不做 userId 驗證，任何 tradeId 都能查到")
        void findById_noOwnershipCheck() {
            Trade trade = Trade.builder()
                    .tradeId("trade-123")
                    .userId("some-other-user")
                    .symbol("BTCUSDT")
                    .status("OPEN")
                    .build();
            when(tradeRepository.findById("trade-123")).thenReturn(Optional.of(trade));

            // ThreadLocal 設的是不同的 userId
            TradeRecordService.setCurrentUserId("my-user");
            try {
                Optional<Trade> result = service.findById("trade-123");

                // 單人模式下不做 ownership check，應該回傳
                assertThat(result).isPresent();
                assertThat(result.get().getTradeId()).isEqualTo("trade-123");
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }

        @Test
        @DisplayName("findEvents — 不做 userId 驗證，任何 tradeId 都能查事件")
        void findEvents_noOwnershipCheck() {
            TradeEvent event = TradeEvent.builder()
                    .tradeId("trade-123")
                    .eventType("ENTRY_PLACED")
                    .build();
            when(tradeEventRepository.findByTradeIdOrderByTimestampAsc("trade-123"))
                    .thenReturn(List.of(event));

            // 單人模式不需要 findById 先驗證歸屬
            List<TradeEvent> events = service.findEvents("trade-123");

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getEventType()).isEqualTo("ENTRY_PLACED");
            // 確認沒有觸發 findById 歸屬檢查
            verify(tradeRepository, never()).findById(anyString());
        }
    }

    // ==================== 去重：單人模式使用配置的 userId ====================

    @Nested
    @DisplayName("去重 — 使用配置的 userId")
    class DedupWithConfiguredUserIdTests {

        @Test
        @DisplayName("isUserDuplicate 用固定 userId 能正確去重")
        void dedup_worksWithFixedUserId() {
            com.trader.shared.config.RiskConfig riskConfig = mock(com.trader.shared.config.RiskConfig.class);
            when(riskConfig.isDedupEnabled()).thenReturn(true);

            SignalDeduplicationService dedupService =
                    new SignalDeduplicationService(tradeRepository, riskConfig);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(94000)
                    .build();

            String userId = "BECK_TEST"; // 模擬 prod 環境的 TRADING_USER_ID

            // 第一次：通過
            boolean first = dedupService.isUserDuplicate(signal, userId);
            assertThat(first).isFalse();

            // 第二次（同 userId + 同 signal）：被擋
            boolean second = dedupService.isUserDuplicate(signal, userId);
            assertThat(second).isTrue();
        }

        @Test
        @DisplayName("generateUserHash 包含 userId 前綴")
        void userHash_containsUserId() {
            com.trader.shared.config.RiskConfig riskConfig = mock(com.trader.shared.config.RiskConfig.class);
            SignalDeduplicationService dedupService =
                    new SignalDeduplicationService(tradeRepository, riskConfig);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(94000)
                    .build();

            String hash1 = dedupService.generateUserHash(signal, "BECK_TEST");
            String hash2 = dedupService.generateUserHash(signal, "default");

            // 不同 userId → 不同 hash
            assertThat(hash1).isNotEqualTo(hash2);

            // 同 userId → 相同 hash
            String hash3 = dedupService.generateUserHash(signal, "BECK_TEST");
            assertThat(hash1).isEqualTo(hash3);
        }
    }
}
