package com.trader.dashboard.controller;

import com.trader.trading.entity.PromptVersion;
import com.trader.trading.service.PromptVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Prompt 版本管理 API — Admin 建立/啟用/列表/預覽
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
public class AdminPromptController {

    private final PromptVersionService promptVersionService;

    @GetMapping
    public ResponseEntity<List<PromptVersion>> listVersions() {
        return ResponseEntity.ok(promptVersionService.getAllVersions());
    }

    @PostMapping
    public ResponseEntity<PromptVersion> createVersion(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String description = body.get("description");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(promptVersionService.createVersion(content, description));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<PromptVersion> activateVersion(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(promptVersionService.activateVersion(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/active")
    public ResponseEntity<PromptVersion> getActivePrompt() {
        return promptVersionService.getActivePrompt()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
