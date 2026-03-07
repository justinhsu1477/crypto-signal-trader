package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradeRecordService.recordLimitEntryFilled() 單元測試
 *
 * 覆蓋：
 * - 找到 OPEN Trade → 更新 entryPrice/entryTime/entryCommission + 寫 Event
 * - 找不到 → 回傳 null，不寫入
 * - 多用戶模式 → 使用 findByUserIdAndEntryOrderIdAndStatus
 */
class TradeRecordServiceLimitEntryTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private MultiUserConfig multiUserConfig;
    private TradeRecordService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        multiUserConfig = mock(MultiUserConfig.class);

        service = new TradeRecordService(
                tradeRepository,
                tradeEventRepository,
                new ObjectMapper(),
                multiUserConfig,
                "system-trader"
        );
    }

    @AfterEach
    void tearDown() {
        TradeRecordService.setCurrentUserId(null);
    }

    // ==================== 單用戶模式 ====================

    @Nested
    @DisplayName("單用戶模式 — LIMIT 入場成交")
    class SingleUserMode {

        @BeforeEach
        void setUp() {
            when(multiUserConfig.isEnabled()).thenReturn(false);
        }

        @Test
        @DisplayName("找到 OPEN Trade → 更新 entryPrice/entryTime/entryCommission + 寫 LIMIT_ENTRY_FILLED 事件")
        void foundOpenTradeUpdatesFields() {
            Trade openTrade = Trade.builder()
                    .tradeId("trade-001")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryOrderId("order-123")
                    .entryPrice(94950.0)  // 委託價（應被覆蓋）
                    .status("OPEN")
                    .build();

            when(tradeRepository.findByEntryOrderIdAndStatus("order-123", "OPEN"))
                    .thenReturn(Optional.of(openTrade));

            Trade result = service.recordLimitEntryFilled(
                    "BTCUSDT", "order-123", 94800.5, 0.262, 12.34, 1700000000000L);

            // 回傳非 null
            assertThat(result).isNotNull();
            assertThat(result.getTradeId()).isEqualTo("trade-001");

            // 驗證欄位已更新
            assertThat(result.getEntryPrice()).isEqualTo(94800.5);
            assertThat(result.getEntryQuantity()).isEqualTo(0.262);
            assertThat(result.getEntryTime()).isNotNull();
            assertThat(result.getEntryCommission()).isEqualTo(12.34);

            // 驗證 save 被呼叫
            verify(tradeRepository).save(openTrade);

            // 驗證寫入 LIMIT_ENTRY_FILLED 事件
            verify(tradeEventRepository).save(argThat((TradeEvent event) ->
                    "trade-001".equals(event.getTradeId()) &&
                    "LIMIT_ENTRY_FILLED".equals(event.getEventType()) &&
                    "order-123".equals(event.getBinanceOrderId()) &&
                    event.getPrice() == 94800.5 &&
                    event.getQuantity() == 0.262
            ));
        }

        @Test
        @DisplayName("commission = 0 → 不更新 entryCommission")
        void zeroCommissionSkipsUpdate() {
            Trade openTrade = Trade.builder()
                    .tradeId("trade-002")
                    .symbol("ETHUSDT")
                    .side("SHORT")
                    .entryOrderId("order-456")
                    .entryCommission(5.0)  // 保留原值
                    .status("OPEN")
                    .build();

            when(tradeRepository.findByEntryOrderIdAndStatus("order-456", "OPEN"))
                    .thenReturn(Optional.of(openTrade));

            Trade result = service.recordLimitEntryFilled(
                    "ETHUSDT", "order-456", 3200.0, 1.5, 0, 1700000000000L);

            assertThat(result).isNotNull();
            // commission = 0 → 保持原值
            assertThat(result.getEntryCommission()).isEqualTo(5.0);

            verify(tradeRepository).save(openTrade);
        }

        @Test
        @DisplayName("找不到 OPEN Trade → 回傳 null，不呼叫 save")
        void notFoundReturnsNull() {
            when(tradeRepository.findByEntryOrderIdAndStatus("order-999", "OPEN"))
                    .thenReturn(Optional.empty());

            Trade result = service.recordLimitEntryFilled(
                    "BTCUSDT", "order-999", 94800.0, 0.1, 5.0, 1700000000000L);

            assertThat(result).isNull();

            // 不應呼叫 save
            verify(tradeRepository, never()).save(any());
            verify(tradeEventRepository, never()).save(any());
        }
    }

    // ==================== 多用戶模式 ====================

    @Nested
    @DisplayName("多用戶模式 — LIMIT 入場成交")
    class MultiUserMode {

        @BeforeEach
        void setUp() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeRecordService.setCurrentUserId("user-abc");
        }

        @Test
        @DisplayName("多用戶模式 → 使用 findByUserIdAndEntryOrderIdAndStatus 查詢")
        void usesUserIdQuery() {
            Trade openTrade = Trade.builder()
                    .tradeId("trade-multi-001")
                    .userId("user-abc")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryOrderId("order-789")
                    .status("OPEN")
                    .build();

            when(tradeRepository.findByUserIdAndEntryOrderIdAndStatus("user-abc", "order-789", "OPEN"))
                    .thenReturn(Optional.of(openTrade));

            Trade result = service.recordLimitEntryFilled(
                    "BTCUSDT", "order-789", 95000.0, 0.3, 8.5, 1700000000000L);

            assertThat(result).isNotNull();
            assertThat(result.getTradeId()).isEqualTo("trade-multi-001");

            // 驗證使用正確的 repository 方法（帶 userId）
            verify(tradeRepository).findByUserIdAndEntryOrderIdAndStatus("user-abc", "order-789", "OPEN");
            // 不應呼叫不帶 userId 的方法
            verify(tradeRepository, never()).findByEntryOrderIdAndStatus(anyString(), anyString());

            verify(tradeRepository).save(openTrade);
        }

        @Test
        @DisplayName("多用戶模式找不到 → 回傳 null")
        void multiUserNotFoundReturnsNull() {
            when(tradeRepository.findByUserIdAndEntryOrderIdAndStatus("user-abc", "order-000", "OPEN"))
                    .thenReturn(Optional.empty());

            Trade result = service.recordLimitEntryFilled(
                    "BTCUSDT", "order-000", 94000.0, 0.1, 5.0, 1700000000000L);

            assertThat(result).isNull();
            verify(tradeRepository, never()).save(any());
        }
    }
}
