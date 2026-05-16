package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MonitorHeartbeatService 單元測試
 *
 * 覆蓋：心跳接收、狀態轉換、逾時檢查、告警發送、告警去重、getStatus
 */
class MonitorHeartbeatServiceTest {

    private NotificationService webhookService;
    private MonitorHeartbeatService service;

    @BeforeEach
    void setUp() {
        webhookService = mock(NotificationService.class);
        service = new MonitorHeartbeatService(webhookService);
    }

    // ==================== receiveHeartbeat ====================

    @Nested
    @DisplayName("receiveHeartbeat — 心跳接收")
    class ReceiveHeartbeatTests {

        @Test
        @DisplayName("正常心跳 — 更新時間和狀態")
        void normalHeartbeat() {
            Map<String, Object> result = service.receiveHeartbeat("connected", "active", null, null);

            assertThat(result.get("received")).isEqualTo(true);
            assertThat(result.get("status")).isEqualTo("ok");
            assertThat(result.get("timestamp")).isNotNull();
        }

        @Test
        @DisplayName("reconnecting 狀態 — 發送斷線告警")
        void reconnectingSendsAlert() {
            service.receiveHeartbeat("reconnecting", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("中斷"),
                    contains("重連"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("reconnecting 重複 — 不重複發送告警")
        void reconnectingDedup() {
            service.receiveHeartbeat("reconnecting", "active", null, null);
            service.receiveHeartbeat("reconnecting", "active", null, null);

            verify(webhookService, times(1)).sendNotificationToAdmins(
                    contains("中斷"), anyString(), eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("reconnecting → connected — 發送恢復通知")
        void recoveryNotification() {
            service.receiveHeartbeat("reconnecting", "active", null, null);
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("恢復"),
                    contains("恢復"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("connected 但未曾斷線 — 不發送恢復通知")
        void connectedWithoutPriorAlertNoRecovery() {
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("AI 狀態監控")
    class AiStatusTests {

        @Test
        @DisplayName("AI disabled — 發送 AI 離線告警")
        void aiDisabledAlert() {
            service.receiveHeartbeat("connected", "disabled", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("AI"),
                    contains("AI Signal Parser"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("AI disabled 重複 — 不重複告警")
        void aiDisabledDedup() {
            service.receiveHeartbeat("connected", "disabled", null, null);
            service.receiveHeartbeat("connected", "disabled", null, null);

            verify(webhookService, times(1)).sendNotificationToAdmins(
                    contains("AI"), anyString(), eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("AI disabled → active — 發送 AI 恢復通知")
        void aiRecoveryNotification() {
            service.receiveHeartbeat("connected", "disabled", null, null);
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("AI Agent 已啟用"),
                    contains("AI 模式"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("aiStatus 為 null — 不更新 AI 狀態")
        void nullAiStatusIgnored() {
            service.receiveHeartbeat("connected", null, null, null);

            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }
    }

    // ==================== checkHeartbeat ====================

    @Nested
    @DisplayName("checkHeartbeat — 逾時檢查")
    class CheckHeartbeatTests {

        @Test
        @DisplayName("從未收到心跳 — 不告警")
        void neverReceivedNoAlert() {
            service.checkHeartbeat();

            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("最近收到心跳 — 不告警")
        void recentHeartbeatNoAlert() {
            service.receiveHeartbeat("connected", "active", null, null);

            service.checkHeartbeat();

            // 只有 receiveHeartbeat 不會觸發通知（因為 connected 且未曾斷線）
            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("心跳逾時 — 發送離線告警")
        @SuppressWarnings("unchecked")
        void timeoutSendsAlert() {
            // 模擬 90+ 秒前收到的心跳
            AtomicReference<Instant> lastHeartbeat =
                    (AtomicReference<Instant>) ReflectionTestUtils.getField(service, "lastHeartbeat");
            lastHeartbeat.set(Instant.now().minusSeconds(100));

            service.checkHeartbeat();

            verify(webhookService).sendNotificationToAdmins(
                    contains("離線"),
                    contains("心跳"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("心跳逾時已告警 — 不重複發送")
        @SuppressWarnings("unchecked")
        void timeoutDedup() {
            AtomicReference<Instant> lastHeartbeat =
                    (AtomicReference<Instant>) ReflectionTestUtils.getField(service, "lastHeartbeat");
            lastHeartbeat.set(Instant.now().minusSeconds(100));

            service.checkHeartbeat();
            service.checkHeartbeat();

            verify(webhookService, times(1)).sendNotificationToAdmins(
                    contains("離線"), anyString(), eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("逾時後恢復心跳 — 重置告警狀態")
        @SuppressWarnings("unchecked")
        void recoveryAfterTimeout() {
            // 先觸發逾時
            AtomicReference<Instant> lastHeartbeat =
                    (AtomicReference<Instant>) ReflectionTestUtils.getField(service, "lastHeartbeat");
            lastHeartbeat.set(Instant.now().minusSeconds(100));
            service.checkHeartbeat();

            // 恢復心跳（alertSent=true → connected → 發恢復通知）
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("恢復"), anyString(), eq(DiscordWebhookService.COLOR_GREEN));
        }
    }

    // ==================== getStatus ====================

    @Nested
    @DisplayName("getStatus — 狀態查詢")
    class GetStatusTests {

        @Test
        @DisplayName("從未收到心跳 — lastHeartbeat=null, online=false")
        void neverReceived() {
            Map<String, Object> status = service.getStatus();

            assertThat(status.get("lastHeartbeat")).isNull();
            assertThat(status.get("online")).isEqualTo(false);
            assertThat(status.get("monitorConnected")).isEqualTo(false);
            assertThat(status.get("secondsSinceLastHeartbeat")).isNull();
            assertThat(status.get("aiParserAvailable")).isEqualTo(false);
            assertThat(status.get("monitorStatus")).isEqualTo("unknown");
            assertThat(status.get("aiStatus")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("剛收到 connected 心跳 — online=true")
        void justReceivedConnected() {
            service.receiveHeartbeat("connected", "active", null, null);

            Map<String, Object> status = service.getStatus();

            assertThat(status.get("online")).isEqualTo(true);
            assertThat(status.get("monitorConnected")).isEqualTo(true);
            assertThat(status.get("aiParserAvailable")).isEqualTo(true);
            assertThat(status.get("monitorStatus")).isEqualTo("connected");
            assertThat(status.get("aiStatus")).isEqualTo("active");
            assertThat(status.get("alertSent")).isEqualTo(false);
        }

        @Test
        @DisplayName("reconnecting 狀態 — online=false")
        void reconnectingNotOnline() {
            service.receiveHeartbeat("reconnecting", "active", null, null);

            Map<String, Object> status = service.getStatus();

            assertThat(status.get("online")).isEqualTo(false);
            assertThat(status.get("monitorStatus")).isEqualTo("reconnecting");
            assertThat(status.get("alertSent")).isEqualTo(true);
        }

        @Test
        @DisplayName("心跳逾時 — online=false")
        @SuppressWarnings("unchecked")
        void timeoutNotOnline() {
            AtomicReference<Instant> lastHeartbeat =
                    (AtomicReference<Instant>) ReflectionTestUtils.getField(service, "lastHeartbeat");
            lastHeartbeat.set(Instant.now().minusSeconds(200));
            ReflectionTestUtils.setField(service, "lastStatus", "connected");

            Map<String, Object> status = service.getStatus();

            assertThat(status.get("online")).isEqualTo(false);
            assertThat((long) status.get("elapsedSeconds")).isGreaterThan(90);
        }
    }

    // ==================== notifySystem → Admin per-user 通知 ====================

    @Nested
    @DisplayName("notifySystem — Admin per-user 通知")
    class NotifySystemTests {

        @Test
        @DisplayName("Discord 斷線 — 發送 admin 告警")
        void disconnectNotifiesAdmin() {
            service.receiveHeartbeat("reconnecting", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("中斷"), anyString(), eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("Discord 恢復 — 發送 admin 通知")
        void recoveryNotifiesAdmin() {
            service.receiveHeartbeat("reconnecting", "active", null, null);
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("恢復"), anyString(), eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("AI 未啟用 — 發送 admin 告警")
        void aiDisabledNotifiesAdmin() {
            service.receiveHeartbeat("connected", "disabled", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("AI"), anyString(), eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("AI 恢復 — 發送 admin 通知")
        void aiRecoveryNotifiesAdmin() {
            service.receiveHeartbeat("connected", "disabled", null, null);
            service.receiveHeartbeat("connected", "active", null, null);

            verify(webhookService).sendNotificationToAdmins(
                    contains("AI Agent 已啟用"), anyString(), eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("心跳逾時離線 — 發送 admin 告警")
        @SuppressWarnings("unchecked")
        void timeoutNotifiesAdmin() {
            AtomicReference<Instant> lastHeartbeat =
                    (AtomicReference<Instant>) ReflectionTestUtils.getField(service, "lastHeartbeat");
            lastHeartbeat.set(Instant.now().minusSeconds(100));

            service.checkHeartbeat();

            verify(webhookService).sendNotificationToAdmins(
                    contains("離線"), anyString(), eq(DiscordWebhookService.COLOR_RED));
        }
    }

    // ==================== Channel Last Seen ====================

    @Nested
    @DisplayName("channelLastSeen — 每頻道最後活動時間")
    class ChannelLastSeenTests {

        @Test
        @DisplayName("初始狀態 — 空 map")
        @SuppressWarnings("unchecked")
        void initialEmpty() {
            Map<String, Object> status = service.getStatus();
            Map<String, Long> lastSeen = (Map<String, Long>) status.get("channelLastSeen");
            assertThat(lastSeen).isEmpty();
        }

        @Test
        @DisplayName("heartbeat 帶 channelLastSeen — getStatus 回傳正確")
        @SuppressWarnings("unchecked")
        void storesChannelLastSeen() {
            Map<String, Long> data = Map.of("ch-001", 1710000000000L, "ch-002", 1710000060000L);
            service.receiveHeartbeat("connected", "active", null, data);

            Map<String, Object> status = service.getStatus();
            Map<String, Long> lastSeen = (Map<String, Long>) status.get("channelLastSeen");
            assertThat(lastSeen).containsEntry("ch-001", 1710000000000L);
            assertThat(lastSeen).containsEntry("ch-002", 1710000060000L);
        }

        @Test
        @DisplayName("channelLastSeen 為 null — 不崩潰，保留舊值")
        @SuppressWarnings("unchecked")
        void nullDoesNotCrash() {
            Map<String, Long> data = Map.of("ch-001", 1710000000000L);
            service.receiveHeartbeat("connected", "active", null, data);
            service.receiveHeartbeat("connected", "active", null, null);

            Map<String, Object> status = service.getStatus();
            Map<String, Long> lastSeen = (Map<String, Long>) status.get("channelLastSeen");
            assertThat(lastSeen).containsEntry("ch-001", 1710000000000L);
        }

        @Test
        @DisplayName("更新 channelLastSeen — 覆蓋舊值")
        @SuppressWarnings("unchecked")
        void updatesOverwriteOld() {
            Map<String, Long> data1 = Map.of("ch-001", 1710000000000L);
            service.receiveHeartbeat("connected", "active", null, data1);

            Map<String, Long> data2 = Map.of("ch-001", 1710000099000L, "ch-003", 1710000050000L);
            service.receiveHeartbeat("connected", "active", null, data2);

            Map<String, Object> status = service.getStatus();
            Map<String, Long> lastSeen = (Map<String, Long>) status.get("channelLastSeen");
            assertThat(lastSeen).containsEntry("ch-001", 1710000099000L);
            assertThat(lastSeen).containsEntry("ch-003", 1710000050000L);
            assertThat(lastSeen).doesNotContainKey("ch-002");
        }
    }

    // ==================== Layer 1 capture watchdog ====================

    @Nested
    @DisplayName("secondsSinceAnyMessage — Layer 1 capture watchdog")
    class CaptureWatchdogTests {

        @Test
        @DisplayName("初始狀態 — secondsSinceAnyMessage null")
        void initialNull() {
            Map<String, Object> status = service.getStatus();
            assertThat(status.get("secondsSinceAnyMessage")).isNull();
        }

        @Test
        @DisplayName("heartbeat 帶 secondsSinceAnyMessage=15000 — getStatus 帶回正確值")
        void storesSecondsSinceAnyMessage() {
            service.receiveHeartbeat("connected", "active", null, null, 15000.0);

            Map<String, Object> status = service.getStatus();
            assertThat(status.get("secondsSinceAnyMessage")).isEqualTo(15000.0);
        }

        @Test
        @DisplayName("舊版 4-arg overload 仍可用（向後相容）— secondsSinceAnyMessage 保持 null")
        void backwardCompatible4ArgOverload() {
            service.receiveHeartbeat("connected", "active", null, null);

            Map<String, Object> status = service.getStatus();
            assertThat(status.get("secondsSinceAnyMessage")).isNull();
        }

        @Test
        @DisplayName("secondsSinceAnyMessage null — 覆蓋舊值（Python 重啟還沒收訊息）")
        void nullOverwritesPreviousValue() {
            service.receiveHeartbeat("connected", "active", null, null, 1234.0);
            // Python 重啟，第一個 heartbeat 帶 null
            service.receiveHeartbeat("connected", "active", null, null, null);

            Map<String, Object> status = service.getStatus();
            assertThat(status.get("secondsSinceAnyMessage")).isNull();
        }
    }

    // ==================== AI Token 用量 ====================

    @Nested
    @DisplayName("AI Token 用量統計")
    class TokenStatsTests {

        @Test
        @DisplayName("初始狀態 — 全部為 0")
        void initialStats() {
            Map<String, Long> stats = service.getDailyTokenStats();
            assertThat(stats.get("callCount")).isEqualTo(0L);
            assertThat(stats.get("promptTokens")).isEqualTo(0L);
            assertThat(stats.get("responseTokens")).isEqualTo(0L);
        }

        @Test
        @DisplayName("收到 token stats — delta 累加")
        void deltaAccumulation() {
            // 第一次 heartbeat: session 累計 call=5, prompt=10000, response=2000
            Map<String, Object> stats1 = Map.of(
                    "call_count", 5, "total_prompt_tokens", 10000, "total_response_tokens", 2000);
            service.receiveHeartbeat("connected", "active", stats1, null);

            Map<String, Long> daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(5L);
            assertThat(daily.get("promptTokens")).isEqualTo(10000L);
            assertThat(daily.get("responseTokens")).isEqualTo(2000L);

            // 第二次 heartbeat: session 累計增加到 call=8, prompt=16000, response=3200
            Map<String, Object> stats2 = Map.of(
                    "call_count", 8, "total_prompt_tokens", 16000, "total_response_tokens", 3200);
            service.receiveHeartbeat("connected", "active", stats2, null);

            daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(8L);    // 5 + (8-5) = 8
            assertThat(daily.get("promptTokens")).isEqualTo(16000L);
            assertThat(daily.get("responseTokens")).isEqualTo(3200L);
        }

        @Test
        @DisplayName("Python 重啟（新值 < 舊值）— 以新值作為增量")
        void pythonRestartResets() {
            // 第一次: session 累計 call=10
            Map<String, Object> stats1 = Map.of(
                    "call_count", 10, "total_prompt_tokens", 20000, "total_response_tokens", 4000);
            service.receiveHeartbeat("connected", "active", stats1, null);

            // Python 重啟: session 累計歸零，回到 call=2
            Map<String, Object> stats2 = Map.of(
                    "call_count", 2, "total_prompt_tokens", 4000, "total_response_tokens", 800);
            service.receiveHeartbeat("connected", "active", stats2, null);

            Map<String, Long> daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(12L);   // 10 + 2 (重啟後增量 = 新值)
            assertThat(daily.get("promptTokens")).isEqualTo(24000L);
        }

        @Test
        @DisplayName("重置 — 全部歸零")
        void reset() {
            Map<String, Object> stats = Map.of(
                    "call_count", 5, "total_prompt_tokens", 10000, "total_response_tokens", 2000);
            service.receiveHeartbeat("connected", "active", stats, null);

            service.resetDailyTokenStats();

            Map<String, Long> daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(0L);
            assertThat(daily.get("promptTokens")).isEqualTo(0L);
            assertThat(daily.get("responseTokens")).isEqualTo(0L);
        }

        @Test
        @DisplayName("null aiTokenStats — 不影響計數")
        void nullTokenStatsIgnored() {
            service.receiveHeartbeat("connected", "active", null, null);

            Map<String, Long> daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(0L);
        }

        @Test
        @DisplayName("無變化的 heartbeat — delta=0 不累加")
        void noDeltaNoAccumulation() {
            Map<String, Object> stats = Map.of(
                    "call_count", 5, "total_prompt_tokens", 10000, "total_response_tokens", 2000);
            service.receiveHeartbeat("connected", "active", stats, null);
            // 同樣的值再送一次（Python session 累計沒變）
            service.receiveHeartbeat("connected", "active", stats, null);

            Map<String, Long> daily = service.getDailyTokenStats();
            assertThat(daily.get("callCount")).isEqualTo(5L); // 沒有重複累加
        }
    }

    // ==================== monitorVersion (Gap 10) ====================

    @Nested
    @DisplayName("monitorVersion — Python git HEAD visibility")
    class MonitorVersionTests {

        @Test
        @DisplayName("receiveHeartbeat 帶 monitorVersion → getStatus 暴露相同字串")
        void receiveHeartbeat_storesMonitorVersion() {
            service.receiveHeartbeat(
                    "connected", "active", null, null, null, "abc1234"
            );

            Map<String, Object> status = service.getStatus();
            assertThat(status).containsEntry("monitorVersion", "abc1234");
        }

        @Test
        @DisplayName("舊 Python 沒帶 monitorVersion → getStatus 給 null（向下相容）")
        void receiveHeartbeat_nullMonitorVersion_returnsNull() {
            service.receiveHeartbeat("connected", "active", null, null, null);

            Map<String, Object> status = service.getStatus();
            assertThat(status).containsKey("monitorVersion");
            assertThat(status.get("monitorVersion")).isNull();
        }
    }
}
