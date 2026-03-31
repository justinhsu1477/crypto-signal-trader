package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.GeminiService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.trading.entity.AnalystDailyMessage;
import com.trader.trading.entity.AnalystReport;
import com.trader.trading.repository.AnalystReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨分析師 AI 日報服務
 *
 * 收集當日所有分析師的訊息，透過 Gemini 產生跨分析師綜合分析報告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalystReportService {

    private final AnalystMessageService analystMessageService;
    private final AnalystReportRepository reportRepository;
    private final GeminiService geminiService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /**
     * 產生指定日期的跨分析師報告
     */
    @Transactional
    public AnalystReport generateReport(LocalDate date) {
        // 檢查是否已存在
        Optional<AnalystReport> existing = reportRepository.findByReportDate(date);
        if (existing.isPresent()) {
            log.info("分析師日報已存在，覆蓋: {}", date);
            reportRepository.delete(existing.get());
        }

        // 收集當日所有分析師訊息
        List<AnalystDailyMessage> messages = analystMessageService.getMessagesByDate(date);
        if (messages.isEmpty()) {
            log.warn("日期 {} 無分析師訊息，跳過報告生成", date);
            throw new IllegalStateException("日期 " + date + " 無分析師訊息");
        }

        log.info("分析師日報生成: {} 位分析師，共 {} 則訊息",
                messages.size(),
                messages.stream().mapToInt(AnalystDailyMessage::getMessageCount).sum());

        // 組裝給 AI 的內容
        String analystContent = formatForAi(messages, date);

        // Gemini 生成報告
        String reportContent = null;
        Optional<String> aiResult = geminiService.generateContent(buildSystemPrompt(), analystContent);
        if (aiResult.isPresent()) {
            reportContent = aiResult.get();
        } else {
            log.warn("Gemini 報告生成失敗，儲存空報告");
        }

        // 結構化摘要（JSON）
        String reportDataJson = buildReportData(messages);

        // 儲存
        AnalystReport report = AnalystReport.builder()
                .reportDate(date)
                .analystCount(messages.size())
                .reportContent(reportContent)
                .reportData(reportDataJson)
                .build();

        AnalystReport saved = reportRepository.save(report);

        // Discord 通知
        sendAdminNotification(saved);

        log.info("分析師日報已產生: {} ({} 位分析師)", date, messages.size());
        return saved;
    }

    // ==================== AI Prompt ====================

    private String buildSystemPrompt() {
        return """
                你是一位專業的加密貨幣分析師報告彙整專家。你會收到多位分析師當天在 Discord 頻道發布的所有訊息。

                請根據所有分析師的訊息，產生一份跨分析師的綜合日報：

                1. **各分析師觀點摘要**：每位分析師的核心觀點、看多/看空立場、重點幣種
                2. **共識與分歧**：多位分析師意見一致的方向，以及有明顯分歧的觀點
                3. **市場情緒總結**：綜合所有分析師的觀點，判斷整體市場情緒（偏多/偏空/中性）
                4. **重點幣種分析**：被多位分析師提及的幣種，彙整各方觀點
                5. **風險提醒**：根據分析師們提到的風險因素，彙整需要注意的事項
                6. **操作建議**：綜合各方觀點後的建議（非投資建議，僅供參考）

                請用繁體中文回答，保持專業但易讀。使用 emoji 標題讓報告更易讀。
                如果某位分析師的訊息大多是閒聊或非交易相關，可以簡要帶過。
                格式要求：段落之間只用一個空行，不要使用連續多個空行。標題與內容之間不需要額外空行。保持緊湊排版。
                """;
    }

    private String formatForAi(List<AnalystDailyMessage> messages, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("日期: ").append(date).append("\n");
        sb.append("分析師數量: ").append(messages.size()).append("\n\n");

        for (AnalystDailyMessage msg : messages) {
            sb.append("========================================\n");
            sb.append("分析師: ").append(msg.getAnalystName()).append("\n");
            sb.append("訊息數: ").append(msg.getMessageCount()).append("\n");
            sb.append("----------------------------------------\n");
            // 截取前 3000 字，避免 token 爆炸
            String content = msg.getContent();
            if (content.length() > 3000) {
                content = content.substring(0, 3000) + "\n...(訊息過長，已截斷)";
            }
            sb.append(content).append("\n\n");
        }

        return sb.toString();
    }

    private String buildReportData(List<AnalystDailyMessage> messages) {
        try {
            List<Map<String, Object>> analysts = messages.stream()
                    .map(msg -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("analystName", msg.getAnalystName());
                        m.put("channelId", msg.getChannelId());
                        m.put("messageCount", msg.getMessageCount());
                        m.put("contentLength", msg.getContent().length());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("analysts", analysts);
            data.put("totalMessages", messages.stream().mapToInt(AnalystDailyMessage::getMessageCount).sum());
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("序列化 reportData 失敗: {}", e.getMessage());
            return "{}";
        }
    }

    // ==================== 通知 ====================

    private void sendAdminNotification(AnalystReport report) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📅 %s\n", report.getReportDate()));
            sb.append(String.format("👥 分析師: %d 位\n", report.getAnalystCount()));

            if (report.getReportContent() != null) {
                String content = report.getReportContent().replaceAll("\n{3,}", "\n\n");
                String preview = content.length() > 500
                        ? content.substring(0, 500) + "..."
                        : content;
                sb.append("\n").append(preview);
            } else {
                sb.append("\n⚠️ AI 報告生成失敗");
            }

            notificationService.sendNotificationToAdmins(
                    "📊 分析師日報 — " + report.getReportDate(),
                    sb.toString(),
                    DiscordWebhookService.COLOR_BLUE);
        } catch (Exception e) {
            log.warn("分析師日報通知發送失敗: {}", e.getMessage());
        }
    }

    // ==================== 查詢 ====================

    public Page<AnalystReport> getReports(int page, int size) {
        return reportRepository.findAllByOrderByReportDateDesc(PageRequest.of(page, size));
    }

    public Optional<AnalystReport> getReportByDate(LocalDate date) {
        return reportRepository.findByReportDate(date);
    }
}
