package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 顧問排程器 + 手動測試 endpoint
 *
 * - 每小時自動觸發 AI 分析（可透過 advisor.enabled 開關）
 * - 提供 /api/advisor/test 方便手動觸發測試
 */
@Slf4j
@Component
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorScheduler {

    private final AdvisorService advisorService;
    private final AdvisorConfig advisorConfig;

    /**
     * 定時觸發 AI 顧問分析
     * 預設每小時整點執行，可透過 advisor.cron-expression 調整
     */
    @Scheduled(cron = "${advisor.cron-expression:0 0 * * * *}", zone = "${app.timezone}")
    public void scheduledAdvisory() {
        if (!advisorConfig.isEnabled()) {
            log.debug("AI Advisor 已停用，跳過");
            return;
        }

        try {
            log.info("AI Advisor 排程觸發，開始分析...");
            advisorService.runAdvisory();
        } catch (Exception e) {
            log.error("AI Advisor 排程執行失敗: {}", e.getMessage(), e);
            // 不拋出例外，排程器繼續下一輪
        }
    }

    /**
     * 手動觸發 AI 顧問分析（測試用）
     * GET /api/advisor/test
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> triggerManually() {
        if (!advisorConfig.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "status", "disabled",
                    "message", "AI Advisor 未啟用，請設定 ADVISOR_ENABLED=true"));
        }

        try {
            advisorService.runAdvisory();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "AI 分析已完成，請查看 Discord"));
        } catch (Exception e) {
            log.error("手動觸發 AI Advisor 失敗: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()));
        }
    }
}
