package com.trader.trading.controller;

import com.trader.shared.model.DiscordRawMessageRequest;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.service.DiscordRawMessageService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Discord 原始訊息封存 API — 由 Python discord-monitor 用 X-Api-Key 認證呼叫。
 *
 * <p>每則通過 channel/guild/author 過濾的訊息（無論最終是否為訊號）都會上報一筆，
 * 提供 audit + 漏單偵測 + eval-harness 訓練資料。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/discord-messages")
@RequiredArgsConstructor
public class DiscordRawMessageController {

    private final DiscordRawMessageService service;
    private final MeterRegistry meterRegistry;

    /**
     * 取得 / 註冊指定 result tag 的 counter。
     * Micrometer 內部已對 (name, tags) 做 cache，重複 register 不會新增 meter，
     * 但仍每次組 Tag list — 在熱路徑可考慮預先快取，目前 archive 量級小不需。
     */
    private Counter counter(String result) {
        return Counter.builder("discord_archive_total")
                .description("Number of /api/discord-messages POSTs received, tagged by result")
                .tag("result", result)
                .register(meterRegistry);
    }

    /**
     * POST /api/discord-messages
     * Body: DiscordRawMessageRequest（snake_case JSON）
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> recordMessage(@RequestBody DiscordRawMessageRequest req) {
        if (req.getMessageId() == null || req.getMessageId().isBlank()) {
            counter("400_missing_message_id").increment();
            return ResponseEntity.badRequest().body(Map.of("error", "message_id required"));
        }
        if (req.getChannelId() == null || req.getChannelId().isBlank()) {
            counter("400_missing_channel_id").increment();
            return ResponseEntity.badRequest().body(Map.of("error", "channel_id required"));
        }
        try {
            DiscordRawMessage saved = service.recordMessage(req);
            counter("ok").increment();
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "message_id", saved.getMessageId(),
                    "signal_id", saved.getSignalId() != null ? saved.getSignalId() : ""
            ));
        } catch (Exception e) {
            log.error("Failed to record discord message {}: {}", req.getMessageId(), e.getMessage(), e);
            counter("500_exception").increment();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
