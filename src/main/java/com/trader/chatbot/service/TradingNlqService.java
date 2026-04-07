package com.trader.chatbot.service;

import com.trader.shared.config.AiConfig;
import com.trader.advisor.service.GeminiService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GenBI 式自然語言查詢服務 — 將使用者的中文問題轉為 SQL，執行後回傳結構化結果。
 *
 * 設計參考：
 * - GenBI PromptServiceImpl: prompt 組裝（schema + RAG + security clause）
 * - GenBI SqlQueryExecutionServiceImpl: SQL sanitize + validate + execute
 * - GenBI SqlAccessValidator: SELECT only 驗證
 *
 * 安全設計：
 * - 非 Admin 強制 WHERE user_id（prompt + server-side validation 雙重保護）
 * - SQL keyword blocklist（禁 DML/DDL）
 * - @Transactional(readOnly = true) 走 read replica
 * - setMaxResults(50) 限制回傳筆數
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingNlqService {

    private final GeminiService geminiService;
    private final AiConfig aiConfig;
    private final EntityManager entityManager;
    private final TradingSchemaProvider schemaProvider;

    private static final int MAX_ROWS = 50;
    private static final int MAX_SQL_LENGTH = 2000;
    private static final int SQL_GENERATION_MAX_TOKENS = 500;
    private static final double SQL_GENERATION_TEMPERATURE = 0.1;

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "CREATE", "MERGE", "EXEC", "EXECUTE"
    );

    private static final Pattern BLOCKED_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", BLOCKED_KEYWORDS) + ")\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern USER_ID_WHERE_PATTERN = Pattern.compile(
            "WHERE\\s+.*user_id\\s*=\\s*'[^']*'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * 主入口：自然語言 → SQL → 執行 → 格式化結果
     */
    @Transactional(readOnly = true)
    public String executeNlq(String userId, boolean isAdmin, String question) {
        try {
            log.info("NLQ 查詢開始: userId={} isAdmin={} question={}", userId, isAdmin, question);

            // 1. 組 prompt
            String systemPrompt = buildSqlPrompt(userId, isAdmin);

            // 2. Gemini 生成 SQL
            Optional<String> generated = geminiService.generateContentWithHistory(
                    systemPrompt, List.of(), question,
                    SQL_GENERATION_MAX_TOKENS, SQL_GENERATION_TEMPERATURE,
                    aiConfig.getDefaultModel()
            );

            if (generated.isEmpty()) {
                return "AI 無法生成查詢，請稍後再試。";
            }

            // 3. Sanitize
            String sql = sanitizeSql(generated.get());
            log.info("NLQ 生成 SQL: {}", sql);

            // 4. Validate
            validateSql(sql);

            // 5. 非 Admin 檢查 user_id 存在
            if (!isAdmin) {
                validateUserIdPresence(sql, userId);
            }

            // 6. 執行（@Transactional 在 executeNlq 層級，確保 read replica 生效）
            // 如果 SQL 已有 LIMIT，不再設 setMaxResults（避免 LIMIT + FETCH FIRST 衝突）
            Query query = entityManager.createNativeQuery(sql);
            if (!sql.toUpperCase().contains("LIMIT")) {
                query.setMaxResults(MAX_ROWS);
            }
            List<?> results = query.getResultList();

            // 7. 格式化
            return formatResults(sql, results);

        } catch (IllegalArgumentException e) {
            log.warn("NLQ 驗證失敗: userId={} error={}", userId, e.getMessage());
            return "查詢被拒絕：" + e.getMessage();
        } catch (Exception e) {
            log.error("NLQ 執行失敗: userId={} question={}", userId, question, e);
            return "查詢執行失敗：" + e.getMessage();
        }
    }

    /**
     * 組裝 SQL 生成 prompt（GenBI PromptServiceImpl 的做法）
     */
    private String buildSqlPrompt(String userId, boolean isAdmin) {
        String securityClause = isAdmin
                ? "- 你可以查詢所有用戶的資料，不需要限制 user_id。"
                : "- 你必須在每個查詢中包含 WHERE user_id = '%s'，這是強制規則，不可省略。".formatted(userId);

        return """
                You are a PostgreSQL SQL expert for a crypto futures trading platform.
                Generate a single read-only SQL query to answer the user's question.

                Rules:
                - Return ONLY the SQL query, no explanation, no markdown code blocks
                - Only generate SELECT statements (or WITH clause ending in SELECT)
                - Do NOT generate DROP, DELETE, UPDATE, INSERT, ALTER, TRUNCATE, GRANT, or any DML/DDL
                - Use PostgreSQL syntax
                - Add LIMIT 50 unless the user specifies otherwise
                - Use column aliases in Chinese for readability (e.g. AS 總損益, AS 勝率)
                %s

                Database schema:
                %s

                %s
                """.formatted(securityClause, schemaProvider.getSchemaContext(),
                schemaProvider.getFewShotExamples(userId, isAdmin));
    }

    /**
     * SQL 清理（GenBI SqlQueryExecutionServiceImpl.sanitizeGeneratedSql 的做法）
     */
    String sanitizeSql(String rawSql) {
        String sql = rawSql.trim();
        // 去除 markdown code block
        sql = sql.replaceFirst("(?is)^```(?:sql)?\\s*", "");
        sql = sql.replaceFirst("(?is)\\s*```$", "");
        // 去除結尾分號
        sql = sql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /**
     * SQL 驗證（簡化版 GenBI SqlAccessValidator）
     */
    void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("生成的 SQL 為空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new IllegalArgumentException("SQL 過長（超過 " + MAX_SQL_LENGTH + " 字元）");
        }

        String upper = sql.toUpperCase().trim();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException("只允許 SELECT 查詢");
        }

        // 禁止多條語句
        if (sql.contains(";")) {
            throw new IllegalArgumentException("不允許多條 SQL 語句");
        }

        // 禁止 DML/DDL keyword
        Matcher matcher = BLOCKED_PATTERN.matcher(sql);
        if (matcher.find()) {
            throw new IllegalArgumentException("SQL 包含禁止的操作：" + matcher.group());
        }
    }

    /**
     * 非 Admin 強制 user_id 過濾（defense-in-depth）
     * 用正則確認 user_id 出現在 WHERE clause 中，不只是 SELECT 或 alias
     */
    void validateUserIdPresence(String sql, String userId) {
        if (!USER_ID_WHERE_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("查詢必須在 WHERE 條件中包含 user_id 過濾");
        }
        if (!sql.contains(userId)) {
            throw new IllegalArgumentException("查詢的 user_id 與當前用戶不符");
        }
    }

    /**
     * 格式化查詢結果為 markdown table
     */
    String formatResults(String sql, List<?> results) {
        if (results == null || results.isEmpty()) {
            return "查詢結果：無資料\n\nSQL: " + sql;
        }

        // 從 SQL 的 SELECT clause 解析 column names
        List<String> columns = extractColumnNames(sql);

        StringBuilder sb = new StringBuilder();
        sb.append("查詢結果（").append(results.size()).append(" 筆）：\n");

        // Header
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");

        // Rows
        for (Object row : results) {
            sb.append("| ");
            if (row instanceof Object[] arr) {
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(formatValue(arr[i]));
                }
            } else {
                // Single column result
                sb.append(formatValue(row));
            }
            sb.append(" |\n");
        }

        sb.append("\nSQL: ").append(sql);
        return sb.toString();
    }

    /**
     * 從 SQL SELECT clause 提取 column aliases
     */
    private List<String> extractColumnNames(String sql) {
        try {
            String upper = sql.toUpperCase();
            int selectEnd = findSelectEnd(upper);
            if (selectEnd < 0) return List.of("結果");

            String selectClause = sql.substring(upper.indexOf("SELECT") + 6, selectEnd).trim();
            String[] parts = selectClause.split(",");
            List<String> names = new ArrayList<>();

            for (String part : parts) {
                String trimmed = part.trim();
                // 找 AS alias
                Pattern asPattern = Pattern.compile("(?i)\\bAS\\s+(\\S+)$");
                Matcher m = asPattern.matcher(trimmed);
                if (m.find()) {
                    names.add(m.group(1).replace("\"", ""));
                } else {
                    // 取最後一個 token
                    String[] tokens = trimmed.split("\\s+");
                    String last = tokens[tokens.length - 1];
                    // 去除 table prefix
                    if (last.contains(".")) {
                        last = last.substring(last.lastIndexOf('.') + 1);
                    }
                    names.add(last);
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("Failed to extract column names from SQL", e);
            return List.of("結果");
        }
    }

    private int findSelectEnd(String upperSql) {
        // 找第一個不在括號內的 FROM
        int depth = 0;
        int fromIndex = -1;
        for (int i = 0; i < upperSql.length() - 3; i++) {
            char c = upperSql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && upperSql.startsWith("FROM", i)
                    && (i == 0 || !Character.isLetterOrDigit(upperSql.charAt(i - 1)))
                    && (i + 4 >= upperSql.length() || !Character.isLetterOrDigit(upperSql.charAt(i + 4)))) {
                fromIndex = i;
                break;
            }
        }
        return fromIndex;
    }

    private String formatValue(Object value) {
        if (value == null) return "-";
        if (value instanceof Double d) return String.format("%.2f", d);
        if (value instanceof Float f) return String.format("%.2f", f);
        return value.toString();
    }
}
