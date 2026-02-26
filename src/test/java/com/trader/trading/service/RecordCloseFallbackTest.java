package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.OrderResult;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * recordClose fallback 測試
 *
 * 驗證 recordClose 在找不到 OPEN 交易時，
 * 能否恢復最近被 STALE_CLEANUP_STARTUP 標為 CANCELLED 的交易並正確記錄平倉。
 */
class RecordCloseFallbackTest {

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

    // ==================== 單人模式 recordClose fallback ====================

    @Nested
    @DisplayName("recordClose fallback — 單人模式")
    class SingleModeFallbackTests {

        @Test
        @DisplayName("OPEN 交易存在 → 正常平倉（不觸發 fallback）")
        void openTradeExists_normalClose() {
            Trade openTrade = createTrade("trade-1", "BTCUSDT", "LONG", "OPEN", 95000.0);
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("close-1").price(96000.0).quantity(0.1).commission(0.04).build();

            Trade result = service.recordClose("BTCUSDT", closeOrder, "MANUAL_CLOSE");

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("CLOSED");
            assertThat(result.getExitPrice()).isEqualTo(96000.0);
            // 不應查詢 CANCELLED 交易
            verify(tradeRepository, never()).findRecentlyStaleCleanedTrades(any(), any());
        }

        @Test
        @DisplayName("無 OPEN 交易 + 有最近被清理的 CANCELLED 交易 → 恢復並記錄平倉")
        void noOpenTrade_hasCancelledFallback_restoreAndClose() {
            // 無 OPEN 交易
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            // 有最近被 STALE_CLEANUP_STARTUP 標為 CANCELLED 的交易
            Trade cancelledTrade = createTrade("trade-2", "BTCUSDT", "LONG", "CANCELLED", 95000.0);
            cancelledTrade.setExitReason("STALE_CLEANUP_STARTUP");
            cancelledTrade.setExitTime(LocalDateTime.now());
            cancelledTrade.setUpdatedAt(LocalDateTime.now());
            when(tradeRepository.findRecentlyStaleCleanedTrades(eq("BTCUSDT"), any(LocalDateTime.class)))
                    .thenReturn(List.of(cancelledTrade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("close-2").price(96000.0).quantity(0.1).commission(0.04).build();

            Trade result = service.recordClose("BTCUSDT", closeOrder, "MANUAL_CLOSE");

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("CLOSED");
            assertThat(result.getExitPrice()).isEqualTo(96000.0);
            assertThat(result.getExitReason()).isEqualTo("MANUAL_CLOSE");

            // 驗證 save 被呼叫多次：恢復 OPEN + 平倉 CLOSED
            verify(tradeRepository, atLeast(2)).save(any(Trade.class));
        }

        @Test
        @DisplayName("無 OPEN 交易 + 無 CANCELLED fallback → 回傳 null")
        void noOpenTrade_noCancelledFallback_returnsNull() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());
            when(tradeRepository.findRecentlyStaleCleanedTrades(eq("BTCUSDT"), any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList());

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("close-3").price(96000.0).quantity(0.1).commission(0.04).build();

            Trade result = service.recordClose("BTCUSDT", closeOrder, "MANUAL_CLOSE");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("CANCELLED 交易但非 STALE_CLEANUP_STARTUP → 不恢復（null）")
        void cancelledTradeWithDifferentReason_notRestored() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());
            // 查詢只找 exitReason=STALE_CLEANUP_STARTUP 的，所以回傳空
            when(tradeRepository.findRecentlyStaleCleanedTrades(eq("BTCUSDT"), any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList());

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("close-4").price(96000.0).quantity(0.1).commission(0.04).build();

            Trade result = service.recordClose("BTCUSDT", closeOrder, "MANUAL_CLOSE");
            assertThat(result).isNull();
        }
    }

    // ==================== 多用戶模式 recordClose fallback ====================

    @Nested
    @DisplayName("recordClose fallback — 多用戶模式")
    class MultiModeFallbackTests {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig = new MultiUserConfig();
            multiUserConfig.setEnabled(true);
            service = new TradeRecordService(tradeRepository, tradeEventRepository,
                    new ObjectMapper(), multiUserConfig, "test-user");
        }

        @Test
        @DisplayName("顯式 userId — 無 OPEN + 有 CANCELLED fallback → 恢復並平倉")
        void explicitUserId_fallbackRestore() {
            TradeRecordService.setCurrentUserId("beck-tsai");
            try {
                when(tradeRepository.findUserOpenTrade("beck-tsai", "BTCUSDT"))
                        .thenReturn(Optional.empty());

                Trade cancelledTrade = createTrade("trade-5", "BTCUSDT", "LONG", "CANCELLED", 95000.0);
                cancelledTrade.setUserId("beck-tsai");
                cancelledTrade.setExitReason("STALE_CLEANUP_STARTUP");
                cancelledTrade.setUpdatedAt(LocalDateTime.now());
                when(tradeRepository.findUserRecentlyStaleCleanedTrades(eq("beck-tsai"), eq("BTCUSDT"), any(LocalDateTime.class)))
                        .thenReturn(List.of(cancelledTrade));

                OrderResult closeOrder = OrderResult.builder()
                        .success(true).orderId("close-5").price(96000.0).quantity(0.143).commission(0.05).build();

                Trade result = service.recordClose("BTCUSDT", closeOrder, "MANUAL_CLOSE", "beck-tsai");

                assertThat(result).isNotNull();
                assertThat(result.getStatus()).isEqualTo("CLOSED");
                assertThat(result.getExitPrice()).isEqualTo(96000.0);
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }

        @Test
        @DisplayName("ThreadLocal userId — 無 OPEN + 有 CANCELLED fallback → 恢復並平倉")
        void threadLocalUserId_fallbackRestore() {
            TradeRecordService.setCurrentUserId("edward-lin");
            try {
                when(tradeRepository.findUserOpenTrade("edward-lin", "ETHUSDT"))
                        .thenReturn(Optional.empty());

                Trade cancelledTrade = createTrade("trade-6", "ETHUSDT", "SHORT", "CANCELLED", 3000.0);
                cancelledTrade.setUserId("edward-lin");
                cancelledTrade.setExitReason("STALE_CLEANUP_STARTUP");
                cancelledTrade.setUpdatedAt(LocalDateTime.now());
                when(tradeRepository.findUserRecentlyStaleCleanedTrades(eq("edward-lin"), eq("ETHUSDT"), any(LocalDateTime.class)))
                        .thenReturn(List.of(cancelledTrade));

                OrderResult closeOrder = OrderResult.builder()
                        .success(true).orderId("close-6").price(2900.0).quantity(1.0).commission(0.03).build();

                Trade result = service.recordClose("ETHUSDT", closeOrder, "MANUAL_CLOSE");

                assertThat(result).isNotNull();
                assertThat(result.getStatus()).isEqualTo("CLOSED");
            } finally {
                TradeRecordService.clearCurrentUserId();
            }
        }
    }

    // ==================== Helper ====================

    private Trade createTrade(String tradeId, String symbol, String side, String status, double entryPrice) {
        return Trade.builder()
                .tradeId(tradeId)
                .symbol(symbol)
                .side(side)
                .status(status)
                .entryPrice(entryPrice)
                .entryQuantity(0.1)
                .userId("test-user")
                .build();
    }
}
