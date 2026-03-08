package com.trader.notification.service;

import com.trader.notification.service.LimitFillBatchService.LimitFillEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LimitFillBatchService 單元測試
 *
 * 覆蓋：
 * - addFill → 佇列累積
 * - flush 空佇列 → 不發送
 * - flush 有事件 → 建構彙總報告 + 發送 Admin 通知
 * - buildReport 格式驗證（含明細上限 10 筆）
 * - drainQueue 清空佇列
 * - 多次 flush → 不重複發送
 */
class LimitFillBatchServiceTest {

    private NotificationService notificationService;
    private LimitFillBatchService batchService;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        batchService = new LimitFillBatchService(notificationService);
    }

    @Nested
    @DisplayName("addFill")
    class AddFill {

        @Test
        @DisplayName("加入事件 → 佇列計數增加")
        void addFillIncrementsCount() {
            batchService.addFill("UserA", "BTCUSDT", "LONG", 65000.0, 0.001);
            assertThat(batchService.getPendingCount()).isEqualTo(1);

            batchService.addFill("UserB", "ETHUSDT", "SHORT", 3200.0, 0.1);
            assertThat(batchService.getPendingCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("flush")
    class Flush {

        @Test
        @DisplayName("空佇列 → 不發送任何通知")
        void emptyQueueNoNotification() {
            batchService.flush();
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("有事件 → 發送彙總報告給 Admin")
        void withEventsFlushSendsReport() {
            batchService.addFill("UserA (a@test.com)", "BTCUSDT", "LONG", 65000.0, 0.001);
            batchService.addFill("UserB (b@test.com)", "ETHUSDT", "SHORT", 3200.0, 0.1);

            batchService.flush();

            verify(notificationService).sendNotificationToAdmins(
                    eq("📊 限價入場成交彙總"),
                    argThat(msg -> msg.contains("成交人數: 2 人")
                            && msg.contains("UserA (a@test.com): BTCUSDT LONG @ 65000.00")
                            && msg.contains("UserB (b@test.com): ETHUSDT SHORT @ 3200.00")),
                    eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("flush 後佇列清空 → 再次 flush 不發送")
        void flushClearsQueueNoDoubleDeliver() {
            batchService.addFill("UserA", "BTCUSDT", "LONG", 65000.0, 0.001);

            batchService.flush();
            assertThat(batchService.getPendingCount()).isZero();

            batchService.flush();
            verify(notificationService, times(1)).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("buildReport")
    class BuildReport {

        @Test
        @DisplayName("單筆成交 → 完整格式")
        void singleFill() {
            List<LimitFillEvent> fills = List.of(
                    new LimitFillEvent("Justin (justin@test.com)", "BTCUSDT", "LONG", 65432.10, 0.0015));

            String report = batchService.buildReport(fills);

            assertThat(report).contains("成交人數: 1 人");
            assertThat(report).contains("成交明細:");
            assertThat(report).contains("- Justin (justin@test.com): BTCUSDT LONG @ 65432.10 × 0.0015");
            assertThat(report).doesNotContain("及其他");
        }

        @Test
        @DisplayName("超過 10 筆 → 顯示前 10 + 省略計數")
        void moreThanTenFillsTruncates() {
            List<LimitFillEvent> fills = new java.util.ArrayList<>();
            for (int i = 0; i < 13; i++) {
                fills.add(new LimitFillEvent("User" + i, "BTCUSDT", "LONG", 65000.0 + i, 0.001));
            }

            String report = batchService.buildReport(fills);

            assertThat(report).contains("成交人數: 13 人");
            assertThat(report).contains("User0");
            assertThat(report).contains("User9");
            assertThat(report).doesNotContain("User10");
            assertThat(report).contains("及其他 3 人");
        }

        @Test
        @DisplayName("剛好 10 筆 → 不顯示省略")
        void exactlyTenFillsNoTruncation() {
            List<LimitFillEvent> fills = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                fills.add(new LimitFillEvent("User" + i, "BTCUSDT", "LONG", 65000.0, 0.001));
            }

            String report = batchService.buildReport(fills);

            assertThat(report).contains("成交人數: 10 人");
            assertThat(report).doesNotContain("及其他");
        }
    }

    @Nested
    @DisplayName("drainQueue")
    class DrainQueue {

        @Test
        @DisplayName("drain 後佇列清空")
        void drainClearsQueue() {
            batchService.addFill("A", "BTCUSDT", "LONG", 65000, 0.001);
            batchService.addFill("B", "ETHUSDT", "SHORT", 3200, 0.1);

            List<LimitFillEvent> drained = batchService.drainQueue();

            assertThat(drained).hasSize(2);
            assertThat(batchService.getPendingCount()).isZero();
        }

        @Test
        @DisplayName("空佇列 drain → 空 list")
        void drainEmptyReturnsEmpty() {
            List<LimitFillEvent> drained = batchService.drainQueue();
            assertThat(drained).isEmpty();
        }
    }
}
