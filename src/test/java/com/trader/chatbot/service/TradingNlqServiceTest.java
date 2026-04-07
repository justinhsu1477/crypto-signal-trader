package com.trader.chatbot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TradingNlqService — SQL sanitize / validate / userId / format")
class TradingNlqServiceTest {

    // 直接測 package-private methods，不需要 Spring context
    private final TradingNlqService service = new TradingNlqService(null, null, null, null);

    private static final String USER_ID = "test-user-001";

    // ==================== sanitizeSql ====================

    @Nested
    @DisplayName("sanitizeSql")
    class SanitizeSqlTests {

        @Test
        @DisplayName("一般 SQL → 不變")
        void plainSql() {
            assertThat(service.sanitizeSql("SELECT * FROM trades"))
                    .isEqualTo("SELECT * FROM trades");
        }

        @Test
        @DisplayName("去除 ```sql 開頭和 ``` 結尾")
        void removeMarkdownCodeBlock() {
            assertThat(service.sanitizeSql("```sql\nSELECT 1\n```"))
                    .isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("去除 ``` 不帶 sql 標記")
        void removeMarkdownCodeBlockNoLang() {
            assertThat(service.sanitizeSql("```\nSELECT 1\n```"))
                    .isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("去除結尾分號")
        void removeTrailingSemicolon() {
            assertThat(service.sanitizeSql("SELECT 1;"))
                    .isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("去除前後空白")
        void trimWhitespace() {
            assertThat(service.sanitizeSql("  SELECT 1  "))
                    .isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("同時去除 markdown + 分號 + 空白")
        void combineAll() {
            assertThat(service.sanitizeSql("  ```sql\n  SELECT 1;  \n```  "))
                    .isEqualTo("SELECT 1");
        }
    }

    // ==================== validateSql ====================

    @Nested
    @DisplayName("validateSql")
    class ValidateSqlTests {

        @Test
        @DisplayName("SELECT 語句 → 通過")
        void validSelect() {
            service.validateSql("SELECT * FROM trades");
        }

        @Test
        @DisplayName("WITH CTE → 通過")
        void validWithCte() {
            service.validateSql("WITH cte AS (SELECT 1) SELECT * FROM cte");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("空/null/blank → 拒絕")
        void rejectEmpty(String sql) {
            assertThatThrownBy(() -> service.validateSql(sql))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SQL 為空");
        }

        @Test
        @DisplayName("超過 2000 字元 → 拒絕")
        void rejectTooLong() {
            String longSql = "SELECT " + "x".repeat(2000);
            assertThatThrownBy(() -> service.validateSql(longSql))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("過長");
        }

        @Test
        @DisplayName("非 SELECT 開頭 → 拒絕")
        void rejectNonSelect() {
            assertThatThrownBy(() -> service.validateSql("UPDATE trades SET status = 'CLOSED'"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只允許 SELECT");
        }

        @Test
        @DisplayName("包含分號 → 拒絕多條語句")
        void rejectMultiStatement() {
            assertThatThrownBy(() -> service.validateSql("SELECT 1; DROP TABLE trades"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("多條 SQL");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM trades; DROP TABLE trades",
                "SELECT * FROM trades WHERE 1=1 UNION SELECT * FROM trades; DELETE FROM trades"
        })
        @DisplayName("包含分號的各種形式 → 拒絕")
        void rejectSemicolonVariants(String sql) {
            assertThatThrownBy(() -> service.validateSql(sql))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE", "GRANT", "CREATE"})
        @DisplayName("包含 DML/DDL keyword → 拒絕")
        void rejectDmlKeywords(String keyword) {
            String sql = "SELECT * FROM trades WHERE 1=1 " + keyword + " something";
            assertThatThrownBy(() -> service.validateSql(sql))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("禁止的操作");
        }

        @Test
        @DisplayName("column 名包含 keyword 子字串（如 updated_at）→ 通過")
        void allowKeywordAsSubstring() {
            // "updated_at" 包含 "UPDATE" 子字串，但不是獨立 keyword
            service.validateSql("SELECT updated_at FROM trades");
        }
    }

    // ==================== validateUserIdPresence ====================

    @Nested
    @DisplayName("validateUserIdPresence")
    class ValidateUserIdPresenceTests {

        @Test
        @DisplayName("WHERE user_id = 'userId' → 通過")
        void validWhereClause() {
            String sql = "SELECT * FROM trades WHERE user_id = '" + USER_ID + "' AND status = 'CLOSED'";
            service.validateUserIdPresence(sql, USER_ID);
        }

        @Test
        @DisplayName("user_id 只在 SELECT 不在 WHERE → 拒絕")
        void rejectUserIdOnlyInSelect() {
            String sql = "SELECT user_id, net_profit FROM trades";
            assertThatThrownBy(() -> service.validateUserIdPresence(sql, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WHERE 條件");
        }

        @Test
        @DisplayName("WHERE 有 user_id 但值不符 → 拒絕")
        void rejectWrongUserId() {
            String sql = "SELECT * FROM trades WHERE user_id = 'other-user'";
            assertThatThrownBy(() -> service.validateUserIdPresence(sql, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("user_id 與當前用戶不符");
        }

        @Test
        @DisplayName("完全沒有 user_id → 拒絕")
        void rejectNoUserId() {
            String sql = "SELECT * FROM trades WHERE status = 'CLOSED'";
            assertThatThrownBy(() -> service.validateUserIdPresence(sql, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WHERE 條件");
        }

        @Test
        @DisplayName("子查詢中有 WHERE user_id → 通過")
        void validSubquery() {
            String sql = "SELECT symbol, SUM(net_profit) FROM trades WHERE user_id = '" + USER_ID + "' GROUP BY symbol";
            service.validateUserIdPresence(sql, USER_ID);
        }
    }

    // ==================== formatResults ====================

    @Nested
    @DisplayName("formatResults")
    class FormatResultsTests {

        @Test
        @DisplayName("空結果 → 顯示無資料")
        void emptyResults() {
            String result = service.formatResults("SELECT 1", List.of());
            assertThat(result).contains("無資料");
            assertThat(result).contains("SQL:");
        }

        @Test
        @DisplayName("null 結果 → 顯示無資料")
        void nullResults() {
            String result = service.formatResults("SELECT 1", null);
            assertThat(result).contains("無資料");
        }

        @Test
        @DisplayName("單欄位結果")
        void singleColumnResult() {
            String sql = "SELECT COUNT(*) AS total FROM trades";
            List<?> results = List.of(42L);

            String result = service.formatResults(sql, results);
            assertThat(result).contains("1 筆");
            assertThat(result).contains("42");
        }

        @Test
        @DisplayName("多欄位結果")
        void multiColumnResult() {
            String sql = "SELECT symbol, net_profit FROM trades";
            List<?> results = List.of(
                    new Object[]{"BTCUSDT", 320.5},
                    new Object[]{"ETHUSDT", -45.2}
            );

            String result = service.formatResults(sql, results);
            assertThat(result).contains("2 筆");
            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("320.50");
            assertThat(result).contains("ETHUSDT");
            assertThat(result).contains("-45.20");
        }

        @Test
        @DisplayName("null 值顯示為 -")
        void nullValueFormatted() {
            String sql = "SELECT symbol, net_profit FROM trades";
            Object[] row = {"BTCUSDT", null};
            List<Object[]> results = new java.util.ArrayList<>();
            results.add(row);

            String result = service.formatResults(sql, results);
            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("- |");
        }

        @Test
        @DisplayName("帶 AS alias 的 SQL → column header 用 alias")
        void columnAlias() {
            String sql = "SELECT SUM(net_profit) AS 總損益 FROM trades";
            List<?> results = List.of(500.0);

            String result = service.formatResults(sql, results);
            assertThat(result).contains("總損益");
        }
    }
}
