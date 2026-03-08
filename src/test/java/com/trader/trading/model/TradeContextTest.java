package com.trader.trading.model;

import com.trader.trading.service.TradeRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        }

        @Test
        void forScheduledTask_不含顯示名稱() {
            TradeContext ctx = TradeContext.forScheduledTask("user-2");

            assertThat(ctx.userId()).isEqualTo("user-2");
            assertThat(ctx.displayName()).isNull();
            assertThat(ctx.broadcastMode()).isFalse();
        }

        @Test
        void forWebSocket_不含顯示名稱() {
            TradeContext ctx = TradeContext.forWebSocket("user-3");

            assertThat(ctx.userId()).isEqualTo("user-3");
            assertThat(ctx.displayName()).isNull();
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
            TradeContext ctx = new TradeContext("user-1", "   ", false);
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
}
