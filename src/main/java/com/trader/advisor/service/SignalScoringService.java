package com.trader.advisor.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.advisor.config.AdvisorConfig;
import com.trader.advisor.dto.RiskLevel;
import com.trader.advisor.dto.SignalScore;
import com.trader.shared.model.TradeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 信號評分服務（影子評分模式）
 *
 * 廣播跟單時非同步呼叫 Gemini 對信號打分（0-100），
 * 純記錄不影響交易執行。
 *
 * 架構：
 * - 專用 scoringExecutor（core=2, max=4, queue=8）— 跟 broadcastExecutor 和 commonPool 完全隔離
 * - bounded queue + AbortPolicy：滿了直接降級回傳 null，不排隊等待
 * - ScoringMetrics：記錄丟棄/完成/失敗次數 + 平均耗時 + 排隊深度
 * - 主流程（下單）完全不等待，零延遲
 * - 只評 ENTRY 信號（CLOSE/MOVE_SL/CANCEL 不評分）
 */
@Slf4j
@Service
public class SignalScoringService {

    private final GeminiService geminiService;
    private final AdvisorConfig advisorConfig;
    private final ThreadPoolExecutor scoringExecutor;
    private final ScoringMetrics scoringMetrics;
    private final Gson gson = new Gson();

    public SignalScoringService(
            GeminiService geminiService,
            AdvisorConfig advisorConfig,
            @Qualifier("scoringExecutor") ThreadPoolExecutor scoringExecutor,
            ScoringMetrics scoringMetrics) {
        this.geminiService = geminiService;
        this.advisorConfig = advisorConfig;
        this.scoringExecutor = scoringExecutor;
        this.scoringMetrics = scoringMetrics;
    }

    // @formatter:off
    private static final String SCORING_SYSTEM_PROMPT = """
        你是加密貨幣合約交易的信號評分 AI。
        你的任務是評估交易信號的品質，給出 0-100 的信心分數。

        評估維度：
        1. 風險報酬比（R:R）：SL 和 TP 之間的比例是否合理
        2. 止損距離比例：SL 距離入場價的百分比是否適當（太窄容易被掃、太寬風險過大）
        3. DCA 信號風險：補倉信號代表第一次入場已虧損，風險偏高

        評分標準：
        - 80-100: 優質信號（R:R ≥ 1:2, 止損距離 1-3%）
        - 60-79: 可執行（R:R ≥ 1:1.5, 止損尚可）
        - 40-59: 一般（R:R < 1:1.5 或止損過寬/過窄）
        - 0-39: 風險偏高（R:R < 1:1 或有明顯風險因素）

        回覆格式（純 JSON，不要 markdown code block）：
        {"confidence":78,"riskLevel":"MEDIUM","reasoning":"簡短原因(50字內)"}

        riskLevel 只能是：LOW / MEDIUM / HIGH
        reasoning 必須用繁體中文，不超過 50 字""";
    // @formatter:on

    /**
     * 非同步評分 — 返回 CompletableFuture，主流程不阻塞
     *
     * 降級策略：
     * - 功能關閉 → completedFuture(null)（零開銷）
     * - 非 ENTRY → completedFuture(null)（零開銷）
     * - 線程池滿 → completedFuture(null) + 記錄 discarded 指標
     * - Gemini 失敗 → null + 記錄 failed 指標
     *
     * @param request 交易請求
     * @return CompletableFuture<SignalScore>，降級時回傳 completedFuture(null)
     */
    public CompletableFuture<SignalScore> scoreAsync(TradeRequest request) {
        // 功能關閉 → 零開銷立即回傳
        if (!advisorConfig.isScoringEnabled()) {
            scoringMetrics.recordSkipped();
            return CompletableFuture.completedFuture(null);
        }

        // 非 ENTRY 信號不評分
        if (!"ENTRY".equalsIgnoreCase(request.getAction())) {
            scoringMetrics.recordSkipped();
            return CompletableFuture.completedFuture(null);
        }

        try {
            // 用專用 scoringExecutor 執行（隔離於 commonPool 和 broadcastExecutor）
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return scoreSignal(request);
                } catch (Exception e) {
                    log.warn("AI 信號評分異常: {}", e.getMessage());
                    scoringMetrics.recordFailed();
                    return null;
                }
            }, scoringExecutor);
        } catch (RejectedExecutionException e) {
            // 線程池已滿（active=max + queue=full）→ 直接降級
            scoringMetrics.recordDiscarded();
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 同步評分邏輯（在 scoringExecutor 的背景線程中執行）
     */
    private SignalScore scoreSignal(TradeRequest request) {
        long startTime = System.currentTimeMillis();

        String userContent = buildScoringContent(request);
        Optional<String> response = geminiService.generateContent(SCORING_SYSTEM_PROMPT, userContent);

        long latencyMs = System.currentTimeMillis() - startTime;

        if (response.isEmpty()) {
            log.warn("AI 信號評分: Gemini 無回應 ({}ms)", latencyMs);
            scoringMetrics.recordFailed();
            return null;
        }

        SignalScore score = parseScoreResponse(response.get(), latencyMs);
        if (score != null) {
            scoringMetrics.recordScored(latencyMs);
            log.info("AI 信號評分: {} {} → {}/100 ({}) [{}ms] — {}",
                    request.getSymbol(), request.getSide(),
                    score.getConfidence(), score.getRiskLevel(),
                    latencyMs, score.getReasoning());
        } else {
            scoringMetrics.recordFailed();
        }
        return score;
    }

    /**
     * 組裝信號參數給 Gemini
     */
    private String buildScoringContent(TradeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("交易信號：\n");
        sb.append("幣種: ").append(request.getSymbol()).append("\n");
        sb.append("方向: ").append(request.getSide()).append("\n");

        if (request.getEntryPrice() != null) {
            sb.append("入場價: ").append(request.getEntryPrice()).append("\n");
        }
        if (request.getStopLoss() != null) {
            sb.append("止損: ").append(request.getStopLoss()).append("\n");
        }
        if (request.getTakeProfit() != null) {
            sb.append("止盈: ").append(request.getTakeProfit()).append("\n");
        }
        if (Boolean.TRUE.equals(request.getIsDca())) {
            sb.append("類型: DCA 補倉（第一次入場已虧損）\n");
        }

        // 計算 R:R 供 Gemini 參考
        if (request.getEntryPrice() != null && request.getStopLoss() != null) {
            double entry = request.getEntryPrice();
            double sl = request.getStopLoss();
            double slDistance = Math.abs(entry - sl);
            double slPercent = (slDistance / entry) * 100;
            sb.append(String.format("止損距離: %.2f%%\n", slPercent));

            if (request.getTakeProfit() != null) {
                double tp = request.getTakeProfit();
                double tpDistance = Math.abs(tp - entry);
                double rr = slDistance > 0 ? tpDistance / slDistance : 0;
                sb.append(String.format("R:R 比: 1:%.2f\n", rr));
            }
        }

        return sb.toString();
    }

    /**
     * 解析 Gemini JSON 回應為 SignalScore
     * 容錯：任何解析失敗都回傳 null，不影響交易
     */
    private SignalScore parseScoreResponse(String responseText, long latencyMs) {
        try {
            // 移除可能的 markdown code block 標記
            String cleaned = responseText.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
            }

            JsonObject json = gson.fromJson(cleaned, JsonObject.class);

            int confidence = json.has("confidence") ? json.get("confidence").getAsInt() : -1;
            String rawRiskLevel = json.has("riskLevel") ? json.get("riskLevel").getAsString() : null;
            String reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : null;

            // 驗證必要欄位（confidence 強制要有；riskLevel 缺值會由 enum 用 confidence 推導）
            if (confidence < 0 || confidence > 100) {
                log.warn("AI 信號評分: confidence 無效 — confidence={}, rawRiskLevel={}", confidence, rawRiskLevel);
                return null;
            }

            RiskLevel riskLevel = RiskLevel.fromGeminiOrInfer(rawRiskLevel, confidence);

            // reasoning 截斷
            if (reasoning != null && reasoning.length() > 100) {
                reasoning = reasoning.substring(0, 97) + "...";
            }

            return SignalScore.builder()
                    .confidence(confidence)
                    .riskLevel(riskLevel)
                    .reasoning(reasoning != null ? reasoning : "")
                    .latencyMs(latencyMs)
                    .build();
        } catch (Exception e) {
            log.warn("AI 信號評分: JSON 解析失敗 — {}, raw={}", e.getMessage(), responseText);
            return null;
        }
    }
}
