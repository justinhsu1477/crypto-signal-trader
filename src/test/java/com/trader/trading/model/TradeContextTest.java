package com.trader.trading.model;

import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class TradeContextTest {

    @AfterEach
    void cleanup() {
        TradeContext.clearThreadLocals();
    }

    // ==================== Factory Methods ====================

    @Nested
    class FactoryMethods {

        @Test
        void forBroadcast_設定所有欄位() {
            TradeContext ctx = TradeContext.forBroadcast("user-1", "Justin (justin@test.com)");

            assertThat(ctx.userId()).isEqualTo("user-1");
            assertThat(ctx.displayName()).isEqualTo("Justin (justin@test.com)");
            assertThat(ctx.broadcastMode()).isTrue();
            assertThat(ctx.apiKey()).isNull();
            assertThat(ctx.secretKey()).isNull();
        }

        @Test
        void forScheduledTask_不含顯示名稱() {
            TradeContext ctx = TradeContext.forScheduledTask("user-2");

            assertThat(ctx.userId()).isEqualTo("user-2");
            assertThat(ctx.displayName()).isNull();
            assertThat(ctx.broadcastMode()).isFalse();
            assertThat(ctx.apiKey()).isNull();
        }

        @Test
        void forWebSocket_legacy_無keys() {
            TradeContext ctx = TradeContext.forWebSocket("user-3");

            assertThat(ctx.userId()).isEqualTo("user-3");
            assertThat(ctx.displayName()).isNull();
            assertThat(ctx.broadcastMode()).isFalse();
            assertThat(ctx.apiKey()).isNull();
            assertThat(ctx.secretKey()).isNull();
        }

        @Test
        void forWebSocket_帶keys_存進context() {
            TradeContext ctx = TradeContext.forWebSocket("user-4", "api-key-xxx", "secret-yyy");

            assertThat(ctx.userId()).isEqualTo("user-4");
            assertThat(ctx.apiKey()).isEqualTo("api-key-xxx");
            assertThat(ctx.secretKey()).isEqualTo("secret-yyy");
            assertThat(ctx.broadcastMode()).isFalse();
        }
    }

    // ==================== effectiveDisplayName ====================

    @Nested
    class EffectiveDisplayName {

        @Test
        void 有displayName時返回displayName() {
            TradeContext ctx = TradeContext.forBroadcast("user-1", "Justin (j@test.com)");
            assertThat(ctx.effectiveDisplayName()).isEqualTo("Justin (j@test.com)");
        }

        @Test
        void displayName為null時fallback到userId() {
            TradeContext ctx = TradeContext.forScheduledTask("user-1");
            assertThat(ctx.effectiveDisplayName()).isEqualTo("user-1");
        }

        @Test
        void displayName為空白時fallback到userId() {
            // 直接 new record 而非 factory（測試 displayName=空白 的邊界）
            TradeContext ctx = new TradeContext("user-1", "   ", false, null, null);
            assertThat(ctx.effectiveDisplayName()).isEqualTo("user-1");
        }
    }

    // ==================== ThreadLocal Bridge ====================

    @Nested
    class ThreadLocalBridge {

        @Test
        void installThreadLocals_設入userId和displayName() {
            TradeContext ctx = TradeContext.forBroadcast("user-1", "Justin");
            ctx.installThreadLocals();

            assertThat(TradeRecordService.getCurrentUserId()).isEqualTo("user-1");
            assertThat(TradeRecordService.getCurrentUserDisplayName()).isEqualTo("Justin");
        }

        @Test
        void installThreadLocals_displayName為null時不設入displayName() {
            TradeContext ctx = TradeContext.forScheduledTask("user-2");
            ctx.installThreadLocals();

            assertThat(TradeRecordService.getCurrentUserId()).isEqualTo("user-2");
            assertThat(TradeRecordService.getCurrentUserDisplayName()).isNull();
        }

        @Test
        void clearThreadLocals_清除所有ThreadLocal() {
            TradeRecordService.setCurrentUserId("user-1");
            TradeRecordService.setCurrentUserDisplayName("Justin");

            TradeContext.clearThreadLocals();

            assertThat(TradeRecordService.getCurrentUserId()).isNull();
            assertThat(TradeRecordService.getCurrentUserDisplayName()).isNull();
        }

        @Test
        void install後clear不殘留() {
            TradeContext ctx = TradeContext.forBroadcast("user-1", "Justin");
            ctx.installThreadLocals();
            TradeContext.clearThreadLocals();

            assertThat(TradeRecordService.getCurrentUserId()).isNull();
            assertThat(TradeRecordService.getCurrentUserDisplayName()).isNull();
        }
    }

    // ==================== Binance Keys Bridge (Issue #52 Phase 1) ====================

    @Nested
    class BinanceKeysBridge {

        /**
         * 因為 BinanceFuturesService 的 CURRENT_USER_KEYS 是 private static ThreadLocal，
         * 透過 reflection 讀出來驗證（避免單純為了測試暴露 getter）。
         */
        private BinanceKeys peekCurrentKeys() throws Exception {
            Field field = BinanceFuturesService.class.getDeclaredField("CURRENT_USER_KEYS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<BinanceKeys> tl = (ThreadLocal<BinanceKeys>) field.get(null);
            return tl.get();
        }

        @AfterEach
        void clearBinanceKeys() {
            BinanceFuturesService.clearCurrentUserKeys();
        }

        @Test
        void forWebSocket_帶keys_install後CURRENT_USER_KEYS有值() throws Exception {
            TradeContext ctx = TradeContext.forWebSocket("user-1", "api-xxx", "secret-yyy");
            ctx.installThreadLocals();

            BinanceKeys keys = peekCurrentKeys();
            assertThat(keys).isNotNull();
            assertThat(keys.apiKey()).isEqualTo("api-xxx");
            assertThat(keys.secretKey()).isEqualTo("secret-yyy");
        }

        @Test
        void forWebSocket_legacy_無keys_install後CURRENT_USER_KEYS為null() throws Exception {
            TradeContext ctx = TradeContext.forWebSocket("user-1");
            ctx.installThreadLocals();

            // 既有 forBroadcast / forScheduledTask 不帶 keys，CURRENT_USER_KEYS 不該被設
            // （setCurrentUserKeys 對 null/blank 直接 noop）
            assertThat(peekCurrentKeys()).isNull();
        }

        @Test
        void apiKey為null時不設入CURRENT_USER_KEYS() throws Exception {
            TradeContext ctx = TradeContext.forWebSocket("user-1", null, "secret-yyy");
            ctx.installThreadLocals();

            assertThat(peekCurrentKeys()).isNull();
        }

        @Test
        void secretKey為blank時不設入CURRENT_USER_KEYS() throws Exception {
            TradeContext ctx = TradeContext.forWebSocket("user-1", "api-xxx", "   ");
            ctx.installThreadLocals();

            assertThat(peekCurrentKeys()).isNull();
        }

        @Test
        void clearThreadLocals_清掉CURRENT_USER_KEYS() throws Exception {
            TradeContext ctx = TradeContext.forWebSocket("user-1", "api-xxx", "secret-yyy");
            ctx.installThreadLocals();
            assertThat(peekCurrentKeys()).isNotNull();

            TradeContext.clearThreadLocals();

            assertThat(peekCurrentKeys()).isNull();
        }

        @Test
        void clearThreadLocals_對沒設過keys的context也安全() throws Exception {
            // forBroadcast 不帶 keys → install 不會 set → clear 不該炸
            TradeContext ctx = TradeContext.forBroadcast("user-1", "Justin");
            ctx.installThreadLocals();
            TradeContext.clearThreadLocals();

            assertThat(peekCurrentKeys()).isNull();
        }
    }
}
