package com.trader.shared.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Docker Health Check 專用端點
 *
 * 純探活、無認證、無業務邏輯、無副作用。
 * 與 /api/heartbeat (Monitor 心跳) 分離，避免 POST + 業務邏輯干擾。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
