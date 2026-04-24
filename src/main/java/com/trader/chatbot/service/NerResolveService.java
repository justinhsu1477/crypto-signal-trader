package com.trader.chatbot.service;

import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 實體解析服務（Named Entity Recognition）
 *
 * 目的：從用戶 query 中辨識可能的業務 entity（訊號來源），
 * 回傳匹配到的候選清單，供 LLM prompt 參考或下游 disambiguation 使用。
 *
 * 背景（為何加）：
 * LLM 若沒明確 entity 白名單，可能：
 * - 瞎編（如先前踩過 "chenge" 這個不在 DB 的名稱）
 * - 混淆（「飛揚」可能指 feiyang / 比特币飞扬 / 比特币飞扬VIP）
 * 透過在 prompt 中「明示 query 對應到哪些已知來源」，
 * 降低 hallucination，提升 tool call 正確率。
 *
 * 設計原則：
 * - **無 LLM 成本**：用字串匹配（contains + 不分大小寫 + 簡繁通用）
 * - **不改變現有行為**：只回傳 mention，由呼叫端決定怎麼用
 * - Scope：目前只做 SignalSource entity；User / Symbol 未來 W? 擴展
 *
 * W5b 範圍：只提供 detection。W5c 才做 ambiguity 回問用戶。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NerResolveService {

    private final SignalSourceConfigRepository sourceRepo;

    /**
     * 解析 query 中的訊號來源 entity
     *
     * @param query 重寫後的查詢文字
     * @return 匹配到的訊號來源清單；無匹配時回空 list
     *
     * 匹配規則：
     * 1. source.name / source.displayName 任一包含 query 裡的字串 → 候選
     * 2. query 包含 source.name / source.displayName → 候選
     * 3. 去重：同一 source 不重複
     */
    public NerResult resolveSources(String query) {
        if (query == null || query.isBlank()) {
            return NerResult.empty();
        }
        String normalized = normalize(query);
        List<SignalSourceConfig> allSources = sourceRepo.findAll().stream()
                .filter(SignalSourceConfig::isEnabled)
                .toList();

        List<SignalSourceConfig> matched = new ArrayList<>();
        for (SignalSourceConfig src : allSources) {
            if (isMatch(normalized, src)) {
                matched.add(src);
            }
        }

        if (!matched.isEmpty() && log.isDebugEnabled()) {
            log.debug("NER 匹配 query='{}' → {} 個來源: {}",
                    preview(query), matched.size(),
                    matched.stream().map(SignalSourceConfig::getName).toList());
        }

        return new NerResult(matched);
    }

    /**
     * 將 NER 結果格式化為 system prompt 附加 context
     * （塞進 LLM 看到的 prompt，降低 hallucination）
     */
    public String formatForPrompt(NerResult result) {
        if (result.isEmpty()) return "";
        List<SignalSourceConfig> sources = result.getSourceMatches();
        StringBuilder sb = new StringBuilder("\n\n---\n訊號來源匹配（從資料庫精確比對）：\n");
        for (SignalSourceConfig src : sources) {
            sb.append("- name=").append(src.getName());
            if (src.getDisplayName() != null && !src.getDisplayName().isBlank()) {
                sb.append(" displayName=").append(src.getDisplayName());
            }
            sb.append(" mode=").append(src.getTradeMode())
                    .append(" routing=").append(src.getRoutingMode())
                    .append("\n");
        }
        sb.append("→ 使用工具時必須使用此清單內的 name；勿自行生成其他名稱。\n");
        return sb.toString();
    }

    /**
     * 匹配邏輯：
     * 1. 雙向 contains：query ⊃ candidate 或 candidate ⊃ query
     * 2. Token 級別（以空白 / 標點分詞）：query 裡任一長度 ≥ 2 的 token
     *    被 candidate 包含 → 視為匹配（覆蓋「feiyang 匹配 feiyang-vip」這類）
     */
    private boolean isMatch(String normalizedQuery, SignalSourceConfig src) {
        return matchesCandidate(normalizedQuery, normalize(src.getName()))
                || matchesCandidate(normalizedQuery, normalize(src.getDisplayName()));
    }

    private boolean matchesCandidate(String query, String candidate) {
        if (candidate.isEmpty() || query.isEmpty()) return false;

        // 雙向 contains
        if (query.contains(candidate) || candidate.contains(query)) return true;

        // Token 級別（英文為主，中文無空白則整段即一個 token，實際上由上面雙向 contains 覆蓋）
        for (String token : query.split("[\\s,，。、:：]+")) {
            if (token.length() >= 2 && candidate.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }

    /** 解析結果（W5b 先只含 source；W? 擴展 user / symbol） */
    public static class NerResult {
        private final List<SignalSourceConfig> sourceMatches;

        public NerResult(List<SignalSourceConfig> sourceMatches) {
            this.sourceMatches = sourceMatches != null ? sourceMatches : List.of();
        }

        public static NerResult empty() {
            return new NerResult(List.of());
        }

        public List<SignalSourceConfig> getSourceMatches() {
            return sourceMatches;
        }

        public boolean isEmpty() {
            return sourceMatches.isEmpty();
        }

        public boolean hasAmbiguousSources() {
            return sourceMatches.size() > 1;
        }

        public List<String> getSourceNames() {
            return sourceMatches.stream()
                    .map(SignalSourceConfig::getName)
                    .collect(Collectors.toList());
        }
    }
}
