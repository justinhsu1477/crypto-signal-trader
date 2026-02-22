package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
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

    private DiscordWebhookService webhookService;
    private MonitorHeartbeatService service;

    @BeforeEach
    void setUp() {
        webhookService = mock(DiscordWebhookService.class);
        service = new MonitorHeartbeatService(webhookService);
    }

    // ==================== receiveHeartbeat ====================

    @Nested
    @DisplayName("receiveHeartbeat — 心跳接收")
    class ReceiveHeartbeatTests {

        @Test
        @DisplayName("正常心跳 — 更新時間和狀態")
        void normalHeartbeat() {
            Map<String, Object> result = service.receiveHeartbeat("connected", "active");

            assertThat(result.get("received")).isEqualTo(true);
            assertThat(result.get("status")).isEqualTo("ok");
            assertThat(result.get("timestamp")).isNotNull();
        }

        @Test
        @DisplayName("reconnecting 狀態 — 發送斷線告警")
        void reconnectingSendsAlert() {
            service.receiveHeartbeat("reconnecting", "active");

            verify(webhookService).sendNotification(
                    contains("中斷"),
                    contains("重連"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("reconnecting 重複 — 不重複發送告警")
        void reconnectingDedup() {
            service.receiveHeartbeat("reconnecting", "active");
            service.receiveHeartbeat("reconnecting", "active");

            verify(webhookService, times(1)).sendNotification(
                    contains("中斷"), anyString(), eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("reconnecting → connected — 發送恢復通知")
        void recoveryNotification() {
            service.receiveHeartbeat("reconnecting", "active");
            service.receiveHeartbeat("connected", "active");

            verify(webhookService).sendNotification(
                    contains("恢復"),
                    contains("恢復"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("connected 但未曾斷線 — 不發送恢復通知")
        void connectedWithoutPriorAlertNoRecovery() {
            service.receiveHeartbeat("connected", "active");

            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("AI 狀態監控")
    class AiStatusTests {

        @Test
        @DisplayName("AI disabled — 發送 AI 離線告警")
        void aiDisabledAlert() {
            service.receiveHeartbeat("connected", "disabled");

            verify(webhookService).sendNotification(
                    contains("AI"),
                    contains("AI Signal Parser"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("AI disabled 重複 — 不重複告警")
        void aiDisabledDedup() {
            service.receiveHeartbeat("connected", "disabled");
            service.receiveHeartbeat("connected", "disabled");

            verify(webhookService, times(1)).sendNotification(
                    contains("AI"), anyString(), eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("AI disabled → active — 發送 AI 恢復通知")
        void aiRecoveryNotification() {
            service.receiveHeartbeat("connected", "disabled");
            service.receiveHeartbeat("connected", "active");

            verify(webhookService).sendNotification(
                    contains("AI Agent 已啟用"),
                    contains("AI 模式"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("aiStatus 為 null — 不更新 AI 狀態")
        void nullAiStatusIgnored() {
            service.receiveHeartbeat("connected", null);

            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
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

            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("最近收到心跳 — 不告警")
        void recentHeartbeatNoAlert() {
            service.receiveHeartbeat("connected", "active");

            service.checkHeartbeat();

            // 只有 receiveHeartbeat 不會觸發通知（因為 connected 且未曾斷線）
            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
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

            verify(webhookService).sendNotification(
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

            verify(webhookService, times(1)).sendNotification(
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
            service.receiveHeartbeat("connected", "active");

            verify(webhookService).sendNotification(
                    contains("恢復"), anyString(), eq(DiscordWebhookService.COLOR_GREEN));
        }
    }

    // ==================== getStatus ====================

    @Nested
    @DisplayName("getStatus — 狀態查詢")
    class GetStatusTests {

        @Test
        @DisplayName("從未收到心跳 — lastHeartbeat=never, online=false")
        void neverReceived() {
            Map<String, Object> status = service.getStatus();

            assertThat(status.get("lastHeartbeat")).isEqualTo("never");
            assertThat(status.get("online")).isEqualTo(false);
            assertThat(status.get("monitorStatus")).isEqualTo("unknown");
            assertThat(status.get("aiStatus")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("剛收到 connected 心跳 — online=true")
        void justReceivedConnected() {
            service.receiveHeartbeat("connected", "active");

            Map<String, Object> status = service.getStatus();

            assertThat(status.get("online")).isEqualTo(true);
            assertThat(status.get("monitorStatus")).isEqualTo("connected");
            assertThat(status.get("aiStatus")).isEqualTo("active");
            assertThat(status.get("alertSent")).isEqualTo(false);
        }

        @Test
        @DisplayName("reconnecting 狀態 — online=false")
        void reconnectingNotOnline() {
            service.receiveHeartbeat("reconnecting", "active");

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
}
