package com.trader.dashboard.controller;

import com.trader.dashboard.dto.UpdateChannelsRequest;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.service.MonitorConfigStore;
import com.trader.trading.service.MonitorHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin Monitor 設定 API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 *
 * 功能：
 * - GET  /api/admin/monitor/channels — 查詢當前監聽頻道 + 連線狀態
 * - PUT  /api/admin/monitor/channels — 更新監聽頻道 → 觸發 gRPC 即時推送
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {

    private final MonitorConfigStore configStore;
    private final MonitorHeartbeatService heartbeatService;

    /**
     * 查詢當前 Monitor 頻道設定 + 連線狀態
     */
    @GetMapping("/channels")
    public ResponseEntity<Map<String, Object>> getChannels() {
        MonitorConfig config = configStore.getCurrentConfig();
        Map<String, Object> heartbeatStatus = heartbeatService.getStatus();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channelIds", config.getChannelIdsList());
        response.put("guildIds", config.getGuildIdsList());
        response.put("authorIds", config.getAuthorIdsList());
        response.put("ignoreKeywords", config.getIgnoreKeywordsList());
        response.put("configVersion", config.getVersion());
        response.put("connectedMonitors", configStore.getConnectedObservers());
        response.put("monitorOnline", heartbeatStatus.get("monitorConnected"));
        response.put("lastHeartbeat", heartbeatStatus.get("lastHeartbeat"));

        return ResponseEntity.ok(response);
    }

    /**
     * 更新監聽頻道設定 → 即時推送到已連線的 Python Monitor
     */
    @PutMapping("/channels")
    public ResponseEntity<Map<String, Object>> updateChannels(
            @RequestBody UpdateChannelsRequest request) {

        if (request.getChannelIds() == null || request.getChannelIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "channelIds 不可為空"
            ));
        }

        configStore.updateConfig(
                request.getChannelIds(),
                request.getGuildIds(),
                request.getAuthorIds(),
                request.getIgnoreKeywords(),
                "admin",
                "admin_update"
        );

        log.info("Admin 更新 Monitor 頻道: channels={}", request.getChannelIds());

        return ResponseEntity.ok(Map.of(
                "message", "頻道設定已更新",
                "channelIds", request.getChannelIds(),
                "configVersion", configStore.getCurrentConfig().getVersion(),
                "connectedMonitors", configStore.getConnectedObservers()
        ));
    }
}
