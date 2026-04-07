package com.trader.trading.controller;

import com.trader.trading.service.AnalystMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 分析師訊息收集 API — Discord Monitor 用 X-Api-Key 認證呼叫
 */
@Slf4j
@RestController
@RequestMapping("/api/analyst-messages")
@RequiredArgsConstructor
public class AnalystMessageController {

    private final AnalystMessageService analystMessageService;

    /**
     * POST /api/analyst-messages
     * Body: { "analyst_name": "xxx", "channel_id": "123", "content": "..." }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> appendMessage(@RequestBody Map<String, String> body) {
        String analystName = body.get("analyst_name");
        String channelId = body.get("channel_id");
        String content = body.get("content");

        if (analystName == null || analystName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "analyst_name is required"));
        }
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        if (channelId == null || channelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel_id is required"));
        }

        analystMessageService.appendMessage(analystName, channelId, content);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "analyst_name", analystName
        ));
    }
}
