package com.trader.dashboard.controller;

import com.trader.shared.util.SecurityUtil;
import com.trader.trading.dto.signalsource.*;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.service.MonitorConfigStore;
import com.trader.trading.service.MonitorHeartbeatService;
import com.trader.trading.service.SignalSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理員訊號來源管理 API（含監聽設定合併功能）
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/signal-sources")
@RequiredArgsConstructor
public class AdminSignalSourceController {

    private final SignalSourceService signalSourceService;
    private final MonitorConfigStore monitorConfigStore;
    private final MonitorHeartbeatService monitorHeartbeatService;

    // ======================== 來源 CRUD ========================

    @GetMapping
    public ResponseEntity<List<SignalSourceResponse>> getAllSources() {
        return ResponseEntity.ok(signalSourceService.getAllSources());
    }

    @PostMapping
    public ResponseEntity<SignalSourceResponse> createSource(@Valid @RequestBody CreateSignalSourceRequest request) {
        SignalSourceConfig created = signalSourceService.createSource(request);
        // 回傳完整的 response（含 assignedUserCount）
        List<SignalSourceResponse> all = signalSourceService.getAllSources();
        SignalSourceResponse response = all.stream()
                .filter(s -> s.getId().equals(created.getId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SignalSourceResponse> getSource(@PathVariable Long id) {
        return signalSourceService.getSourceById(id)
                .map(source -> {
                    List<SignalSourceResponse> all = signalSourceService.getAllSources();
                    return all.stream()
                            .filter(s -> s.getId().equals(id))
                            .findFirst()
                            .map(ResponseEntity::ok)
                            .orElse(ResponseEntity.notFound().build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<SignalSourceResponse> updateSource(
            @PathVariable Long id,
            @RequestBody UpdateSignalSourceRequest request) {
        try {
            signalSourceService.updateSource(id, request);
            List<SignalSourceResponse> all = signalSourceService.getAllSources();
            SignalSourceResponse response = all.stream()
                    .filter(s -> s.getId().equals(id))
                    .findFirst()
                    .orElseThrow();
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 更新 customPrompt — 獨立端點，便於 admin UI 加二次確認 + 強制 audit。
     * 一般欄位的 update 走 PUT /{id}，這裡只處理 high-risk 欄位。
     */
    @PutMapping("/{id}/custom-prompt")
    public ResponseEntity<?> updateCustomPrompt(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomPromptRequest body,
            HttpServletRequest request) {
        try {
            String adminId = SecurityUtil.getCurrentUserId();
            String ip = resolveClientIp(request);
            signalSourceService.updateCustomPrompt(id, body.getCustomPrompt(), body.getReason(), adminId, ip);
            List<SignalSourceResponse> all = signalSourceService.getAllSources();
            SignalSourceResponse response = all.stream()
                    .filter(s -> s.getId().equals(id))
                    .findFirst()
                    .orElseThrow();
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        // 對齊 application.yml 設定的 ip-header（Cloudflare 環境用 CF-Connecting-IP）
        String header = request.getHeader("CF-Connecting-IP");
        if (header != null && !header.isBlank()) return header;
        header = request.getHeader("X-Forwarded-For");
        if (header != null && !header.isBlank()) {
            int comma = header.indexOf(',');
            return comma > 0 ? header.substring(0, comma).trim() : header.trim();
        }
        return request.getRemoteAddr();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteSource(@PathVariable Long id) {
        try {
            signalSourceService.deleteSource(id);
            return ResponseEntity.ok(Map.of("message", "訊號來源已刪除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ======================== 用戶綁定 ========================

    @GetMapping("/{id}/users")
    public ResponseEntity<List<UserAssignmentResponse>> getSourceUsers(@PathVariable Long id) {
        return ResponseEntity.ok(signalSourceService.getUsersForSource(id));
    }

    @PostMapping("/{id}/users")
    public ResponseEntity<?> assignUsers(
            @PathVariable Long id,
            @Valid @RequestBody AssignUserRequest request) {
        try {
            List<UserAssignmentResponse> results = signalSourceService.assignUsers(id, request.getUserIds());
            return ResponseEntity.ok(results);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/users/{userId}")
    public ResponseEntity<Map<String, String>> unassignUser(
            @PathVariable Long id,
            @PathVariable String userId) {
        signalSourceService.unassignUser(id, userId);
        return ResponseEntity.ok(Map.of("message", "已解除綁定"));
    }

    @PutMapping("/{id}/users/{userId}")
    public ResponseEntity<Map<String, String>> toggleUserAssignment(
            @PathVariable Long id,
            @PathVariable String userId,
            @RequestBody Map<String, Boolean> body) {
        try {
            boolean enabled = body.getOrDefault("enabled", true);
            signalSourceService.toggleUserAssignment(id, userId, enabled);
            return ResponseEntity.ok(Map.of("message", enabled ? "已啟用" : "已停用"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ======================== 績效查詢 ========================

    @GetMapping("/performance")
    public ResponseEntity<List<SignalSourcePerformanceDto>> getAllPerformance(
            @RequestParam(defaultValue = "all") String period) {
        return ResponseEntity.ok(signalSourceService.getAllSourcePerformances(period));
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<SignalSourcePerformanceDto> getSourcePerformance(
            @PathVariable Long id,
            @RequestParam(defaultValue = "all") String period) {
        try {
            return ResponseEntity.ok(signalSourceService.getSourcePerformance(id, period));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ======================== 模擬交易明細 ========================

    @GetMapping("/{id}/paper-trades")
    public ResponseEntity<Page<PaperTradeDetailResponse>> getPaperTrades(
            @PathVariable Long id,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(signalSourceService.getPaperTrades(id, status, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ======================== Monitor 狀態 + 全局設定（合併自監聽設定） ========================

    /**
     * 查詢 Monitor 連線狀態 + 當前設定
     */
    @GetMapping("/monitor-status")
    public ResponseEntity<Map<String, Object>> getMonitorStatus() {
        MonitorConfig config = monitorConfigStore.getCurrentConfig();
        Map<String, Object> heartbeatStatus = monitorHeartbeatService.getStatus();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channelIds", config.getChannelIdsList());
        response.put("guildIds", config.getGuildIdsList());
        response.put("authorIds", config.getAuthorIdsList());
        response.put("ignoreKeywords", config.getIgnoreKeywordsList());
        response.put("configVersion", config.getVersion());
        response.put("connectedMonitors", monitorConfigStore.getConnectedObservers());
        response.put("monitorOnline", heartbeatStatus.get("monitorConnected"));
        response.put("lastHeartbeat", heartbeatStatus.get("lastHeartbeat"));
        response.put("channelLastSeen", heartbeatStatus.get("channelLastSeen"));

        return ResponseEntity.ok(response);
    }

    /**
     * 更新全局監聽設定（authorIds、ignoreKeywords）
     * channelIds / guildIds 由 SignalSourceConfig CRUD 自動管理
     */
    @PutMapping("/monitor-settings")
    public ResponseEntity<Map<String, Object>> updateGlobalSettings(
            @RequestBody UpdateGlobalSettingsRequest request) {

        MonitorConfig current = monitorConfigStore.getCurrentConfig();

        monitorConfigStore.updateConfig(
                current.getChannelIdsList(),      // 保留（由 source CRUD 管理）
                current.getGuildIdsList(),         // 保留（由 source CRUD 管理）
                request.getAuthorIds(),
                request.getIgnoreKeywords(),
                current.getSourcesList(),          // 保留既有 sources
                "admin",
                "global_settings_update"
        );

        log.info("Admin 更新全局設定: authorIds={}, ignoreKeywords={}",
                request.getAuthorIds(), request.getIgnoreKeywords());

        return ResponseEntity.ok(Map.of(
                "message", "全局設定已更新",
                "configVersion", monitorConfigStore.getCurrentConfig().getVersion(),
                "connectedMonitors", monitorConfigStore.getConnectedObservers()
        ));
    }
}
