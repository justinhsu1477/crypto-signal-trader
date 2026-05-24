package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.TradeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Guard 防誤判 CLOSE: Gemini 把 MOVE_SL（如「做成本保護止損修改入場價75100」）
 * 誤判成 CLOSE 時，payload 結構長這樣：
 *   {action:CLOSE, close_ratio:null, new_stop_loss:75100}
 *
 * 這個組合在正常用法**結構上矛盾**（要全平為什麼還設止損），所以可確定性偵測 + 自動轉 MOVE_SL。
 *
 * 「漏關（可手動補關）優於誤平（資金損失不可救）」是設計取捨。
 */
class SuspiciousClosePayloadGuardTest {

    private DiscordWebhookService discordWebhookService;
    private SuspiciousClosePayloadGuard guard;

    @BeforeEach
    void setUp() {
        discordWebhookService = mock(DiscordWebhookService.class);
        guard = new SuspiciousClosePayloadGuard(discordWebhookService);
        ReflectionTestUtils.setField(guard, "enabled", true);
    }

    @Nested
    @DisplayName("應該觸發 (PASS_THROUGH=false, action 轉成 MOVE_SL)")
    class ShouldConvert {

        @Test
        @DisplayName("經典誤判 case: CLOSE + close_ratio=null + new_stop_loss=75100")
        void classicMisjudgmentCase() {
            TradeRequest req = baseClose();
            req.setNewStopLoss(75100.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.CONVERTED);
            assertThat(req.getAction()).isEqualTo("MOVE_SL");
            assertThat(req.getNewStopLoss()).isEqualTo(75100.0);
            assertThat(req.getCloseRatio()).isNull();
            verify(discordWebhookService, atLeastOnce()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("action lowercase 'close' 也要正確 match (case-insensitive)")
        void caseInsensitiveAction() {
            TradeRequest req = baseClose();
            req.setAction("close");
            req.setNewStopLoss(60000.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.CONVERTED);
            assertThat(req.getAction()).isEqualTo("MOVE_SL");
        }

        @Test
        @DisplayName("有 new_take_profit 也保留: convert 後 take_profit 不應該被吃掉")
        void preservesNewTakeProfit() {
            TradeRequest req = baseClose();
            req.setNewStopLoss(75100.0);
            req.setNewTakeProfit(80000.0);

            guard.inspect(req);

            assertThat(req.getAction()).isEqualTo("MOVE_SL");
            assertThat(req.getNewStopLoss()).isEqualTo(75100.0);
            assertThat(req.getNewTakeProfit()).isEqualTo(80000.0);
        }
    }

    @Nested
    @DisplayName("不應該觸發 (PASS_THROUGH，request 不變)")
    class ShouldPassThrough {

        @Test
        @DisplayName("合法全平: CLOSE + close_ratio=null + new_stop_loss=null")
        void legitimateFullClose() {
            TradeRequest req = baseClose();
            // close_ratio null, new_stop_loss null

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("CLOSE");
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("合法部分平倉 + 止損移動: CLOSE + close_ratio=0.5 + new_stop_loss=75000")
        void legitimatePartialCloseWithSlMove() {
            TradeRequest req = baseClose();
            req.setCloseRatio(0.5);
            req.setNewStopLoss(75000.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("CLOSE");
            assertThat(req.getCloseRatio()).isEqualTo(0.5);
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("純 MOVE_SL: action 不是 CLOSE，不該被誤觸發")
        void alreadyMoveSL() {
            TradeRequest req = baseClose();
            req.setAction("MOVE_SL");
            req.setNewStopLoss(75100.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("MOVE_SL");
        }

        @Test
        @DisplayName("ENTRY 訊號帶 close_ratio 也不被誤判 (不同 action)")
        void entryActionNotAffected() {
            TradeRequest req = baseClose();
            req.setAction("ENTRY");
            req.setCloseRatio(0.5);  // 異常但不該被 CLOSE guard 動

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("ENTRY");
        }

        @Test
        @DisplayName("CLOSE + close_ratio=1.0 (全平用 1.0 表示而非 null) 也不該觸發")
        void closeRatioOneNotTriggered() {
            TradeRequest req = baseClose();
            req.setCloseRatio(1.0);
            req.setNewStopLoss(75100.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("CLOSE");
        }
    }

    @Nested
    @DisplayName("Kill switch / edge cases")
    class KillSwitchAndEdges {

        @Test
        @DisplayName("disabled=false → 即使 payload 符合也不轉 (確保有 kill switch)")
        void killSwitchDisabled() {
            ReflectionTestUtils.setField(guard, "enabled", false);
            TradeRequest req = baseClose();
            req.setNewStopLoss(75100.0);  // 經典誤判 case payload

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            assertThat(req.getAction()).isEqualTo("CLOSE");
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("null request → PASS_THROUGH (不 NPE)")
        void nullRequest() {
            SuspiciousClosePayloadGuard.Result result = guard.inspect(null);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("null action → PASS_THROUGH (不 NPE)")
        void nullAction() {
            TradeRequest req = baseClose();
            req.setAction(null);
            req.setNewStopLoss(75100.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.PASS_THROUGH);
            verifyNoInteractions(discordWebhookService);
        }

        @Test
        @DisplayName("Discord 通知失敗時不可影響 convert 結果")
        void notificationFailureSwallowedNotBlockingConvert() {
            doThrow(new RuntimeException("webhook down"))
                    .when(discordWebhookService).sendNotification(anyString(), anyString(), anyInt());
            TradeRequest req = baseClose();
            req.setNewStopLoss(75100.0);

            SuspiciousClosePayloadGuard.Result result = guard.inspect(req);

            // 通知掛掉，但 conversion 必須完成
            assertThat(result).isEqualTo(SuspiciousClosePayloadGuard.Result.CONVERTED);
            assertThat(req.getAction()).isEqualTo("MOVE_SL");
        }
    }

    // ===== helpers =====
    private static TradeRequest baseClose() {
        TradeRequest req = new TradeRequest();
        req.setAction("CLOSE");
        req.setSymbol("BTCUSDT");
        return req;
    }
}
