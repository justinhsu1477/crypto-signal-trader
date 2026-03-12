package com.trader.dashboard.controller;

import com.trader.trading.dto.signalsource.*;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.service.SignalSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理員訊號來源管理 API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/signal-sources")
@RequiredArgsConstructor
public class AdminSignalSourceController {

    private final SignalSourceService signalSourceService;

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
    public ResponseEntity<List<SignalSourcePerformanceDto>> getAllPerformance() {
        return ResponseEntity.ok(signalSourceService.getAllSourcePerformances());
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<SignalSourcePerformanceDto> getSourcePerformance(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(signalSourceService.getSourcePerformance(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
