package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.GeminiService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.config.AppConstants;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.DailySignalReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 每日訊號日報服務
 *
 * 每天 23:59（台灣時間）自動產生：
 * 1. 結構化統計（按 source / symbol 分組）
 * 2. Gemini AI 宏觀分析
 * 3. 存儲到 DB + Discord 通知 Admin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySignalReportService {

    private static final int MIN_SIGNALS_FOR_AI = 3;

    private final BroadcastLogRepository broadcastLogRepository;
    private final DailySignalReportRepository dailySignalReportRepository;
    private final GeminiService geminiService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // ==================== 排程 ====================

    /**
     * 每日 23:59 台灣時間自動產生訊號日報
     */
    @Scheduled(cron = "0 59 23 * * *", zone = "${app.timezone}")
    public void generateDailyReport() {
        try {
            LocalDate today = LocalDate.now(AppConstants.ZONE_ID);
            generateReportForDate(today);
        } catch (Exception e) {
            log.error("每日訊號日報產生失敗: {}", e.getMessage(), e);
        }
    }

    // ==================== 核心邏輯 ====================

    /**
     * 產生指定日期的訊號日報（支援手動補跑）
     */
    public DailySignalReport generateReportForDate(LocalDate date) {
        // 檢查是否已存在
        Optional<DailySignalReport> existing = dailySignalReportRepository.findByReportDate(date);
        if (existing.isPresent()) {
            log.info("日報已存在，覆蓋: {}", date);
            dailySignalReportRepository.delete(existing.get());
        }

        // 1. 查詢當日 BroadcastLog [startOfDay, nextDay) — 避免漏掉 23:59:59.xxx
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime nextDay = date.plusDays(1).atStartOfDay();
        List<BroadcastLog> logs = broadcastLogRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(startOfDay, nextDay);

        log.info("日報統計 {}: {} 條訊號", date, logs.size());

        // 2. 結構化統計
        Map<String, Object> reportData = buildReportData(logs);

        // 3. AI 分析（訊號太少則跳過）
        String aiAnalysis = null;
        int aiTokensUsed = 0;
        if (logs.size() >= MIN_SIGNALS_FOR_AI) {
            String statsText = formatStatsForAi(reportData, date);
            Optional<String> aiResult = geminiService.generateContent(
                    buildSystemPrompt(), statsText);
            if (aiResult.isPresent()) {
                aiAnalysis = aiResult.get();
            }
        }

        // 4. 組裝 Entity
        String reportDataJson;
        try {
            reportDataJson = objectMapper.writeValueAsString(reportData);
        } catch (Exception e) {
            log.warn("序列化 reportData 失敗: {}", e.getMessage());
            reportDataJson = "{}";
        }

        int longCount = (int) reportData.getOrDefault("longCount", 0);
        int shortCount = (int) reportData.getOrDefault("shortCount", 0);
        int totalSources = reportData.containsKey("sourceStats")
                ? ((List<?>) reportData.get("sourceStats")).size() : 0;
        Double avgConfidence = (Double) reportData.get("avgConfidence");

        DailySignalReport report = DailySignalReport.builder()
                .reportDate(date)
                .totalSignals(logs.size())
                .totalSources(totalSources)
                .longCount(longCount)
                .shortCount(shortCount)
                .avgConfidence(avgConfidence)
                .reportData(reportDataJson)
                .aiAnalysis(aiAnalysis)
                .aiTokensUsed(aiTokensUsed)
                .build();

        DailySignalReport saved = dailySignalReportRepository.save(report);

        // 5. Discord 通知 Admin
        sendAdminNotification(saved);

        log.info("日報已產生: {} ({}條訊號, {}個來源)", date, logs.size(), totalSources);
        return saved;
    }

    // ==================== 統計邏輯 ====================

    /**
     * 建構結構化統計數據
     */
    Map<String, Object> buildReportData(List<BroadcastLog> logs) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (logs.isEmpty()) {
            data.put("longCount", 0);
            data.put("shortCount", 0);
            data.put("avgConfidence", null);
            data.put("sourceStats", List.of());
            data.put("symbolStats", List.of());
            return data;
        }

        // 整體統計
        long longCount = logs.stream().filter(l -> "LONG".equalsIgnoreCase(l.getSide())).count();
        long shortCount = logs.stream().filter(l -> "SHORT".equalsIgnoreCase(l.getSide())).count();
        OptionalDouble avgConf = logs.stream()
                .filter(l -> l.getAiConfidence() != null)
                .mapToInt(BroadcastLog::getAiConfidence)
                .average();

        data.put("longCount", (int) longCount);
        data.put("shortCount", (int) shortCount);
        data.put("avgConfidence", avgConf.isPresent() ? Math.round(avgConf.getAsDouble() * 10.0) / 10.0 : null);

        // 按 source 分組
        Map<String, List<BroadcastLog>> bySource = logs.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getSourceAuthor() != null ? l.getSourceAuthor() : "unknown"));

        List<Map<String, Object>> sourceStats = bySource.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .map(e -> {
                    List<BroadcastLog> sourceLogs = e.getValue();
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("source", e.getKey());
                    stat.put("count", sourceLogs.size());
                    stat.put("long", sourceLogs.stream().filter(l -> "LONG".equalsIgnoreCase(l.getSide())).count());
                    stat.put("short", sourceLogs.stream().filter(l -> "SHORT".equalsIgnoreCase(l.getSide())).count());

                    // 只統計 ENTRY 的成功率
                    List<BroadcastLog> entries = sourceLogs.stream()
                            .filter(l -> "ENTRY".equalsIgnoreCase(l.getSignalAction()))
                            .toList();
                    stat.put("entries", entries.size());

                    OptionalDouble sourceAvgConf = sourceLogs.stream()
                            .filter(l -> l.getAiConfidence() != null)
                            .mapToInt(BroadcastLog::getAiConfidence)
                            .average();
                    stat.put("avgConfidence", sourceAvgConf.isPresent()
                            ? Math.round(sourceAvgConf.getAsDouble() * 10.0) / 10.0 : null);

                    // 訊號動作分布
                    Map<String, Long> actionDist = sourceLogs.stream()
                            .collect(Collectors.groupingBy(
                                    l -> l.getSignalAction() != null ? l.getSignalAction() : "UNKNOWN",
                                    Collectors.counting()));
                    stat.put("actions", actionDist);

                    return stat;
                })
                .toList();

        data.put("sourceStats", sourceStats);

        // 按 symbol 分組（Top 10）
        Map<String, Long> symbolCounts = logs.stream()
                .filter(l -> l.getSymbol() != null)
                .collect(Collectors.groupingBy(BroadcastLog::getSymbol, Collectors.counting()));

        List<Map<String, Object>> symbolStats = symbolCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("symbol", e.getKey());
                    stat.put("count", e.getValue());
                    return stat;
                })
                .toList();

        data.put("symbolStats", symbolStats);

        // 訊號動作分布（整體）
        Map<String, Long> actionDist = logs.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getSignalAction() != null ? l.getSignalAction() : "UNKNOWN",
                        Collectors.counting()));
        data.put("actionDistribution", actionDist);

        return data;
    }

    // ==================== AI 分析 ====================

    private String buildSystemPrompt() {
        return """
                你是一位專業的加密貨幣訊號分析師。根據今日的訊號統計數據，提供以下分析：

                1. **整體市場情緒**：根據 LONG/SHORT 比例和訊號數量判斷市場方向偏好
                2. **來源品質評估**：分析各訊號來源的活躍度和 AI 信心分數表現
                3. **熱門幣種觀察**：最活躍的交易對及其可能的市場含義
                4. **風險提醒**：基於數據的潛在風險警示
                5. **明日建議**：根據今日趨勢的操作建議

                請用繁體中文回答，保持簡潔專業，每個要點 2-3 句話。
                使用 emoji 標題讓報告更易讀。
                """;
    }

    private String formatStatsForAi(Map<String, Object> reportData, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("日期: ").append(date).append("\n\n");

        sb.append("=== 整體統計 ===\n");
        sb.append("總訊號數: ").append(reportData.get("longCount")).append(" LONG + ")
                .append(reportData.get("shortCount")).append(" SHORT\n");
        if (reportData.get("avgConfidence") != null) {
            sb.append("平均 AI 信心: ").append(reportData.get("avgConfidence")).append("/100\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, Long> actions = (Map<String, Long>) reportData.get("actionDistribution");
        if (actions != null) {
            sb.append("動作分布: ").append(actions).append("\n");
        }

        sb.append("\n=== 來源統計 ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceStats = (List<Map<String, Object>>) reportData.get("sourceStats");
        if (sourceStats != null) {
            for (Map<String, Object> s : sourceStats) {
                sb.append("- ").append(s.get("source"))
                        .append(": ").append(s.get("count")).append("條訊號")
                        .append(" (").append(s.get("long")).append("L/").append(s.get("short")).append("S)");
                if (s.get("avgConfidence") != null) {
                    sb.append(" 信心:").append(s.get("avgConfidence"));
                }
                sb.append("\n");
            }
        }

        sb.append("\n=== 熱門幣種 Top 10 ===\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> symbolStats = (List<Map<String, Object>>) reportData.get("symbolStats");
        if (symbolStats != null) {
            for (Map<String, Object> s : symbolStats) {
                sb.append("- ").append(s.get("symbol")).append(": ").append(s.get("count")).append("次\n");
            }
        }

        return sb.toString();
    }

    // ==================== 通知 ====================

    private void sendAdminNotification(DailySignalReport report) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📅 %s\n", report.getReportDate()));
            sb.append(String.format("📊 訊號: %d 條 | 來源: %d 個\n",
                    report.getTotalSignals(), report.getTotalSources()));
            sb.append(String.format("📈 LONG: %d | SHORT: %d\n",
                    report.getLongCount(), report.getShortCount()));

            if (report.getAvgConfidence() != null) {
                sb.append(String.format("🤖 平均信心: %.1f/100\n", report.getAvgConfidence()));
            }

            if (report.getAiAnalysis() != null) {
                // Discord 有 2000 字元限制，截取前 500 字
                String preview = report.getAiAnalysis().length() > 500
                        ? report.getAiAnalysis().substring(0, 500) + "..."
                        : report.getAiAnalysis();
                sb.append("\n").append(preview);
            } else if (report.getTotalSignals() < MIN_SIGNALS_FOR_AI) {
                sb.append("\n💡 訊號不足 ").append(MIN_SIGNALS_FOR_AI).append(" 條，跳過 AI 分析");
            }

            notificationService.sendNotificationToAdmins(
                    "📋 每日訊號日報 — " + report.getReportDate(),
                    sb.toString(),
                    DiscordWebhookService.COLOR_BLUE);
        } catch (Exception e) {
            log.warn("日報通知發送失敗: {}", e.getMessage());
        }
    }

    // ==================== 查詢 ====================

    public Page<DailySignalReport> getReports(int page, int size) {
        return dailySignalReportRepository.findAllByOrderByReportDateDesc(PageRequest.of(page, size));
    }

    public Optional<DailySignalReport> getReportById(Long id) {
        return dailySignalReportRepository.findById(id);
    }
}
