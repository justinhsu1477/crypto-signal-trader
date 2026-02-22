package com.trader.trading.service;

import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SignalDeduplicationService 單元測試
 *
 * 覆蓋：
 * - Hash 生成一致性
 * - ENTRY 去重（內存 + DB 雙層）
 * - CANCEL 去重（30 秒窗口）
 * - 快取清理邏輯
 */
class SignalDeduplicationServiceTest {

    private TradeRepository tradeRepository;
    private RiskConfig riskConfig;
    private SignalDeduplicationService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        riskConfig = mock(RiskConfig.class);
        when(riskConfig.isDedupEnabled()).thenReturn(true);

        service = new SignalDeduplicationService(tradeRepository, riskConfig);
    }

    // ==================== Hash 生成 ====================

    @Nested
    @DisplayName("雜湊生成")
    class HashGenerationTests {

        @Test
        @DisplayName("相同訊號產生相同 hash")
        void sameSignalSameHash() {
            TradeSignal s1 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            TradeSignal s2 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            assertThat(service.generateHash(s1)).isEqualTo(service.generateHash(s2));
        }

        @Test
        @DisplayName("不同價格產生不同 hash")
        void differentPriceDifferentHash() {
            TradeSignal s1 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            TradeSignal s2 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(96000).stopLoss(94000).build();

            assertThat(service.generateHash(s1)).isNotEqualTo(service.generateHash(s2));
        }

        @Test
        @DisplayName("不同方向產生不同 hash")
        void differentSideDifferentHash() {
            TradeSignal s1 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            TradeSignal s2 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.SHORT)
                    .entryPriceLow(95000).stopLoss(94000).build();

            assertThat(service.generateHash(s1)).isNotEqualTo(service.generateHash(s2));
        }

        @Test
        @DisplayName("DCA side=null 使用 'DCA' 替代")
        void dcaNullSideUseDCA() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(null)
                    .entryPriceLow(95000).stopLoss(0).build();

            String hash = service.generateHash(signal);
            assertThat(hash).isNotEmpty();
            assertThat(hash).hasSize(64); // SHA-256 hex = 64 chars
        }

        @Test
        @DisplayName("hash 為 SHA-256 格式（64 hex chars）")
        void hashIsSHA256Format() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("ETHUSDT").side(TradeSignal.Side.SHORT)
                    .entryPriceLow(3500).stopLoss(3600).build();

            String hash = service.generateHash(signal);
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]{64}");
        }
    }

    // ==================== ENTRY 去重 ====================

    @Nested
    @DisplayName("ENTRY 去重")
    class EntryDeduplicationTests {

        @Test
        @DisplayName("首次進場 — 不重複")
        void firstEntryNotDuplicate() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            assertThat(service.isDuplicate(signal)).isFalse();
        }

        @Test
        @DisplayName("5 分鐘內重複 — 攔截（內存層）")
        void duplicateWithinWindowBlocked() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            // 第一次不重複
            assertThat(service.isDuplicate(signal)).isFalse();
            // 第二次重複（內存快取命中）
            assertThat(service.isDuplicate(signal)).isTrue();
        }

        @Test
        @DisplayName("DB 有 OPEN 交易 — 重複")
        void dbHasOpenTradeIsDuplicate() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("ETHUSDT").side(TradeSignal.Side.SHORT)
                    .entryPriceLow(3500).stopLoss(3600).build();

            // 內存中沒有，但 DB 有
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(true);

            assertThat(service.isDuplicate(signal)).isTrue();
        }

        @Test
        @DisplayName("dedup 關閉 — 全部放行")
        void dedupDisabledAllowsAll() {
            when(riskConfig.isDedupEnabled()).thenReturn(false);

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            assertThat(service.isDuplicate(signal)).isFalse();
            assertThat(service.isDuplicate(signal)).isFalse();
            verify(tradeRepository, never()).existsBySignalHashAndCreatedAtAfter(any(), any());
        }
    }

    // ==================== CANCEL 去重 ====================

    @Nested
    @DisplayName("CANCEL 去重")
    class CancelDeduplicationTests {

        @Test
        @DisplayName("首次取消 — 不重複")
        void firstCancelNotDuplicate() {
            assertThat(service.isCancelDuplicate("BTCUSDT")).isFalse();
        }

        @Test
        @DisplayName("30 秒內重複 — 攔截")
        void duplicateWithin30sBlocked() {
            assertThat(service.isCancelDuplicate("BTCUSDT")).isFalse();
            assertThat(service.isCancelDuplicate("BTCUSDT")).isTrue();
        }

        @Test
        @DisplayName("不同 symbol 不互相影響")
        void differentSymbolsIndependent() {
            assertThat(service.isCancelDuplicate("BTCUSDT")).isFalse();
            assertThat(service.isCancelDuplicate("ETHUSDT")).isFalse();
        }

        @Test
        @DisplayName("dedup 關閉 — 全部放行")
        void dedupDisabledAllowsAll() {
            when(riskConfig.isDedupEnabled()).thenReturn(false);
            assertThat(service.isCancelDuplicate("BTCUSDT")).isFalse();
            assertThat(service.isCancelDuplicate("BTCUSDT")).isFalse();
        }
    }

    // ==================== Per-User 去重（Execution-level） ====================

    @Nested
    @DisplayName("Per-User 去重 (isUserDuplicate)")
    class UserDeduplicationTests {

        @Test
        @DisplayName("同一訊號、不同用戶 → 不互相阻擋")
        void sameSignalDifferentUsersNotBlocked() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            // userA 先執行 — 通過
            assertThat(service.isUserDuplicate(signal, "user-A")).isFalse();
            // userB 用同一訊號 — 也通過（hash 不同因為含 userId）
            assertThat(service.isUserDuplicate(signal, "user-B")).isFalse();
            // userC 也通過
            assertThat(service.isUserDuplicate(signal, "user-C")).isFalse();
        }

        @Test
        @DisplayName("同一用戶、同一訊號 → 第二次被擋")
        void sameUserSameSignalBlocked() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            assertThat(service.isUserDuplicate(signal, "user-A")).isFalse();
            assertThat(service.isUserDuplicate(signal, "user-A")).isTrue();
        }

        @Test
        @DisplayName("同一用戶、不同訊號 → 不互相阻擋")
        void sameUserDifferentSignalsNotBlocked() {
            TradeSignal signal1 = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            TradeSignal signal2 = TradeSignal.builder()
                    .symbol("ETHUSDT").side(TradeSignal.Side.SHORT)
                    .entryPriceLow(3500).stopLoss(3600).build();

            assertThat(service.isUserDuplicate(signal1, "user-A")).isFalse();
            assertThat(service.isUserDuplicate(signal2, "user-A")).isFalse();
        }

        @Test
        @DisplayName("dedup 關閉 → 全部放行")
        void dedupDisabledAllowsAll() {
            when(riskConfig.isDedupEnabled()).thenReturn(false);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            assertThat(service.isUserDuplicate(signal, "user-A")).isFalse();
            assertThat(service.isUserDuplicate(signal, "user-A")).isFalse();
        }
    }

    // ==================== Per-User Hash 生成 ====================

    @Nested
    @DisplayName("Per-User Hash 生成 (generateUserHash)")
    class UserHashGenerationTests {

        @Test
        @DisplayName("不同 userId 產生不同 hash")
        void differentUserIdDifferentHash() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            String hashA = service.generateUserHash(signal, "user-A");
            String hashB = service.generateUserHash(signal, "user-B");

            assertThat(hashA).isNotEqualTo(hashB);
        }

        @Test
        @DisplayName("user hash 與全局 hash 不同")
        void userHashDiffersFromGlobalHash() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            String globalHash = service.generateHash(signal);
            String userHash = service.generateUserHash(signal, "user-A");

            assertThat(userHash).isNotEqualTo(globalHash);
        }

        @Test
        @DisplayName("相同 userId + 相同訊號 → 相同 hash")
        void sameUserSameSignalSameHash() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            String hash1 = service.generateUserHash(signal, "user-A");
            String hash2 = service.generateUserHash(signal, "user-A");

            assertThat(hash1).isEqualTo(hash2);
        }
    }

    // ==================== Signal-level 去重 ====================

    @Nested
    @DisplayName("Signal-level 去重 (isSignalProcessed)")
    class SignalProcessedTests {

        @Test
        @DisplayName("isSignalProcessed 行為與 isDuplicate 一致")
        void signalProcessedBehavesLikeIsDuplicate() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            // 首次不重複
            assertThat(service.isSignalProcessed(signal)).isFalse();
            // 第二次重複
            assertThat(service.isSignalProcessed(signal)).isTrue();
        }
    }

    // ==================== 廣播場景整合 ====================

    @Nested
    @DisplayName("廣播場景：signal-level + per-user 聯合")
    class BroadcastScenarioTests {

        @Test
        @DisplayName("模擬廣播流程：signal-level 通過 → 3 個用戶全部通過 per-user 去重")
        void broadcastScenario_allUsersPass() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            // Step 1: signal-level 通過（廣播入口）
            assertThat(service.isSignalProcessed(signal)).isFalse();

            // Step 2: 3 個用戶的 per-user 去重全部通過
            assertThat(service.isUserDuplicate(signal, "user-A")).isFalse();
            assertThat(service.isUserDuplicate(signal, "user-B")).isFalse();
            assertThat(service.isUserDuplicate(signal, "user-C")).isFalse();
        }

        @Test
        @DisplayName("模擬重複廣播：signal-level 第二次被擋")
        void broadcastScenario_duplicateSignalBlocked() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            // 第一次廣播通過
            assertThat(service.isSignalProcessed(signal)).isFalse();

            // Discord 重發同一訊號 → signal-level 擋住，不會再進入廣播
            assertThat(service.isSignalProcessed(signal)).isTrue();
        }
    }

    // ==================== 快取清理 ====================

    @Nested
    @DisplayName("快取清理")
    class CacheCleanupTests {

        @Test
        @DisplayName("超過 500 筆 — 觸發清理")
        @SuppressWarnings("unchecked")
        void triggerCleanupAboveThreshold() throws Exception {
            // 透過反射存取 recentSignals map
            Field cacheField = SignalDeduplicationService.class.getDeclaredField("recentSignals");
            cacheField.setAccessible(true);
            ConcurrentHashMap<String, Long> cache =
                    (ConcurrentHashMap<String, Long>) cacheField.get(service);

            // 填入 501 個過期的條目
            long expired = System.currentTimeMillis() - 600_000; // 10 分鐘前
            for (int i = 0; i < 501; i++) {
                cache.put("expired-" + i, expired);
            }

            // 執行一次 isDuplicate（會觸發 cleanupIfNeeded）
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BNBUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(600).stopLoss(590).build();
            when(tradeRepository.existsBySignalHashAndCreatedAtAfter(anyString(), any()))
                    .thenReturn(false);

            service.isDuplicate(signal);

            // 過期的 501 條應被清理，只剩剛插入的 1 條
            assertThat(cache.size()).isLessThanOrEqualTo(2); // hash + possibly one more
        }
    }

    // ==================== 邊界情況：null / 預設 userId ====================

    @Nested
    @DisplayName("邊界情況：null / 預設 userId")
    class NullAndDefaultUserIdTests {

        @Test
        @DisplayName("null userId — 不拋 NPE，能正常去重")
        void isUserDuplicate_nullUserId_noNPE() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            // 第一次不應拋 NPE 且通過
            assertThat(service.isUserDuplicate(signal, null)).isFalse();
            // null userId 第二次同 signal 被擋
            assertThat(service.isUserDuplicate(signal, null)).isTrue();
        }

        @Test
        @DisplayName("固定 userId（如 BECK_TEST）— 能正確去重且不與其他 userId 衝突")
        void isUserDuplicate_fixedUserId_blocksCorrectly() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000).stopLoss(94000).build();

            // BECK_TEST 第一次通過
            assertThat(service.isUserDuplicate(signal, "BECK_TEST")).isFalse();
            // BECK_TEST 第二次被擋
            assertThat(service.isUserDuplicate(signal, "BECK_TEST")).isTrue();

            // 不同 userId 不受影響
            assertThat(service.isUserDuplicate(signal, "test-user")).isFalse();
        }
    }
}
