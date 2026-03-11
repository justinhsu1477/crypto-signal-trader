package com.trader.dashboard.controller;

import com.trader.dashboard.dto.UpdateChannelsRequest;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.service.MonitorConfigStore;
import com.trader.trading.service.MonitorHeartbeatService;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminMonitorController 單元測試
 *
 * 覆蓋：GET channels、PUT channels（正常 + 空值驗證）
 */
class AdminMonitorControllerTest {

    private MonitorConfigStore configStore;
    private MonitorHeartbeatService heartbeatService;
    private AdminMonitorController controller;

    @BeforeEach
    void setUp() {
        configStore = mock(MonitorConfigStore.class);
        heartbeatService = mock(MonitorHeartbeatService.class);
        controller = new AdminMonitorController(configStore, heartbeatService);
    }

    // ==================== GET /channels ====================

    @Nested
    @DisplayName("getChannels — 查詢頻道設定")
    class GetChannelsTests {

        @Test
        @DisplayName("返回頻道設定和狀態")
        void returnsConfigAndStatus() {
            MonitorConfig config = MonitorConfig.newBuilder()
                    .addAllChannelIds(List.of("ch1", "ch2"))
                    .addAllGuildIds(List.of("g1"))
                    .setVersion(3)
                    .build();
            when(configStore.getCurrentConfig()).thenReturn(config);
            when(configStore.getConnectedObservers()).thenReturn(1);
            when(heartbeatService.getStatus()).thenReturn(Map.of(
                    "monitorConnected", true,
                    "lastHeartbeat", "2024-01-01T00:00:00Z"
            ));

            ResponseEntity<Map<String, Object>> response = controller.getChannels();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("channelIds")).isEqualTo(List.of("ch1", "ch2"));
            assertThat(body.get("configVersion")).isEqualTo(3L);
            assertThat(body.get("connectedMonitors")).isEqualTo(1);
            assertThat(body.get("monitorOnline")).isEqualTo(true);
        }
    }

    // ==================== PUT /channels ====================

    @Nested
    @DisplayName("updateChannels — 更新頻道設定")
    class UpdateChannelsTests {

        @Test
        @DisplayName("正常更新 — 200 + 觸發 configStore")
        void normalUpdate() {
            UpdateChannelsRequest request = new UpdateChannelsRequest();
            request.setChannelIds(List.of("new-ch1", "new-ch2"));
            request.setGuildIds(List.of("g1"));

            MonitorConfig afterConfig = MonitorConfig.newBuilder()
                    .addAllChannelIds(List.of("new-ch1", "new-ch2"))
                    .setVersion(5)
                    .build();
            when(configStore.getCurrentConfig()).thenReturn(afterConfig);
            when(configStore.getConnectedObservers()).thenReturn(1);

            ResponseEntity<Map<String, Object>> response = controller.updateChannels(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(configStore).updateConfig(
                    List.of("new-ch1", "new-ch2"),
                    List.of("g1"),
                    null,
                    null,
                    "admin",
                    "admin_update"
            );
        }

        @Test
        @DisplayName("channelIds 為空 — 400 Bad Request")
        void emptyChannelIds() {
            UpdateChannelsRequest request = new UpdateChannelsRequest();
            request.setChannelIds(List.of());

            ResponseEntity<Map<String, Object>> response = controller.updateChannels(request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsKey("error");
            verify(configStore, never()).updateConfig(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("channelIds 為 null — 400 Bad Request")
        void nullChannelIds() {
            UpdateChannelsRequest request = new UpdateChannelsRequest();
            request.setChannelIds(null);

            ResponseEntity<Map<String, Object>> response = controller.updateChannels(request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verify(configStore, never()).updateConfig(any(), any(), any(), any(), any(), any());
        }
    }
}
