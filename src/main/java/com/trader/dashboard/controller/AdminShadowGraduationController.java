package com.trader.dashboard.controller;

import com.trader.trading.dto.signalsource.ShadowGraduationResult;
import com.trader.trading.service.ShadowGraduationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理員 SHADOW 畢業評估 API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@RestController
@RequestMapping("/api/admin/shadow-graduation")
@RequiredArgsConstructor
public class AdminShadowGraduationController {

    private final ShadowGraduationService shadowGraduationService;

    @GetMapping
    public ResponseEntity<List<ShadowGraduationResult>> getShadowGraduation() {
        return ResponseEntity.ok(shadowGraduationService.evaluateAll());
    }
}
