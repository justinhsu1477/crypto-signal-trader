package com.trader.trading.controller;

import com.trader.shared.config.AppConstants;
import com.trader.trading.entity.AnalystDailyMessage;
import com.trader.trading.entity.AnalystReport;
import com.trader.trading.service.AnalystMessageService;
import com.trader.trading.service.AnalystReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Admin 分析師日報管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/analyst-report")
@RequiredArgsConstructor
public class AdminAnalystReportController {

    private final AnalystReportService reportService;
    private final AnalystMessageService messageService;

    /**
     * POST /api/admin/analyst-report/generate?date=2026-03-30
     * 觸發指定日期的報告生成（預設今天）
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now(AppConstants.ZONE_ID);
        }

        try {
            AnalystReport report = reportService.generateReport(date);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("reportDate", report.getReportDate().toString());
            result.put("analystCount", report.getAnalystCount());
            result.put("hasContent", report.getReportContent() != null);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/analyst-report?date=2026-03-30
     * 查詢指定日期的報告
     */
    @GetMapping
    public ResponseEntity<?> getReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now(AppConstants.ZONE_ID);
        }

        Optional<AnalystReport> report = reportService.getReportByDate(date);
        if (report.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report.get());
    }

    /**
     * GET /api/admin/analyst-report/list?page=0&size=10
     * 分頁查詢報告列表
     */
    @GetMapping("/list")
    public ResponseEntity<Page<AnalystReport>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reportService.getReports(page, size));
    }

    /**
     * GET /api/admin/analyst-report/messages?date=2026-03-30
     * 查詢指定日期各分析師的原始訊息
     */
    @GetMapping("/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now(AppConstants.ZONE_ID);
        }

        List<AnalystDailyMessage> messages = messageService.getMessagesByDate(date);
        List<Map<String, Object>> result = messages.stream()
                .map(msg -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("analystName", msg.getAnalystName());
                    m.put("channelId", msg.getChannelId());
                    m.put("messageCount", msg.getMessageCount());
                    m.put("contentLength", msg.getContent().length());
                    m.put("contentPreview", msg.getContent().length() > 200
                            ? msg.getContent().substring(0, 200) + "..."
                            : msg.getContent());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
