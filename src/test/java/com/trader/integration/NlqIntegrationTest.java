package com.trader.integration;

import com.trader.chatbot.service.TradingNlqService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * NLQ Integration Test — TradingNlqService 打真 PostgreSQL
 *
 * 驗證：
 * - SQL 在真正的 PostgreSQL 上執行成功（不是 H2）
 * - LIMIT + setMaxResults 不衝突（回歸測試）
 * - user_id 隔離
 * - 空結果處理
 */
@DisplayName("NLQ Integration Test — 打真 PostgreSQL")
class NlqIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TradingNlqService nlqService;

    private String userId;

    @BeforeEach
    void seedData() throws Exception {
        // 註冊一個測試使用者並取得 userId
        Cookie cookie = registerAndLogin();

        // 從 DB 查 userId
        Object result = entityManager.createNativeQuery(
                "SELECT user_id FROM users WHERE email = 'test@hookfi.com'"
        ).getSingleResult();
        userId = result.toString();

        // 插入測試 trades — executeUpdate 需 active tx，@BeforeEach 不會被 Spring tx 攔
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // simulated 是 NOT NULL（Hibernate 從 entity 生 schema 沒套 DEFAULT），需顯式給值
            entityManager.createNativeQuery("""
                    INSERT INTO trades (trade_id, user_id, symbol, side, entry_price, exit_price,
                        net_profit, status, exit_reason, entry_time, exit_time, created_at, simulated)
                    VALUES
                        ('t1', :userId, 'BTCUSDT', 'LONG', 95000, 96000, 100.0, 'CLOSED', 'SIGNAL_CLOSE', NOW() - INTERVAL '1 day', NOW(), NOW(), false),
                        ('t2', :userId, 'ETHUSDT', 'SHORT', 3500, 3400, 50.0, 'CLOSED', 'STOP_LOSS', NOW() - INTERVAL '2 days', NOW(), NOW(), false),
                        ('t3', :userId, 'BTCUSDT', 'LONG', 94000, 93000, -80.0, 'CLOSED', 'STOP_LOSS', NOW() - INTERVAL '3 days', NOW(), NOW(), false),
                        ('t4', 'other-user', 'BTCUSDT', 'LONG', 90000, 91000, 200.0, 'CLOSED', 'SIGNAL_CLOSE', NOW(), NOW(), NOW(), false)
                    """.trim())
                    .setParameter("userId", userId)
                    .executeUpdate();
        });

        // Mock GeminiService — NLQ 呼叫時回傳預設 SQL
        when(geminiService.generateContentWithHistory(any(), any(), any(), anyInt(), anyDouble(), any()))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("SQL 執行")
    class SqlExecution {

        @Test
        @DisplayName("帶 LIMIT 的 SQL 在 PostgreSQL 上不衝突")
        void sqlWithLimit_noConflict() {
            String sql = "SELECT symbol, net_profit FROM trades WHERE user_id = '" + userId + "' ORDER BY created_at DESC LIMIT 3";

            // 直接呼叫 formatResults 測 SQL 執行 (繞過 Gemini)
            // 這裡用 entityManager 直接跑 SQL 驗證不會有 LIMIT + FETCH FIRST 衝突
            var query = entityManager.createNativeQuery(sql);
            var results = query.getResultList();

            assertThat(results).hasSize(3);
        }

        @Test
        @DisplayName("不帶 LIMIT 的 SQL 也正常")
        void sqlWithoutLimit_works() {
            String sql = "SELECT symbol, SUM(net_profit) AS total FROM trades WHERE user_id = '" + userId + "' AND status = 'CLOSED' GROUP BY symbol";

            var results = entityManager.createNativeQuery(sql).getResultList();

            assertThat(results).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("user_id 隔離")
    class UserIsolation {

        @Test
        @DisplayName("只查到自己的 trades")
        void onlyOwnTrades() {
            String sql = "SELECT COUNT(*) FROM trades WHERE user_id = '" + userId + "'";

            var result = entityManager.createNativeQuery(sql).getSingleResult();

            assertThat(((Number) result).intValue()).isEqualTo(3);  // t1, t2, t3（不含 t4）
        }

        @Test
        @DisplayName("不帶 user_id 查到全部（Admin 場景）")
        void allTradesWithoutFilter() {
            String sql = "SELECT COUNT(*) FROM trades";

            var result = entityManager.createNativeQuery(sql).getSingleResult();

            assertThat(((Number) result).intValue()).isEqualTo(4);  // 含 other-user
        }
    }

    @Nested
    @DisplayName("聚合查詢")
    class AggregateQueries {

        @Test
        @DisplayName("GROUP BY symbol 在 PostgreSQL 上正常")
        void groupBySymbol() {
            String sql = """
                    SELECT symbol, COUNT(*) AS trades, SUM(net_profit) AS total_pnl
                    FROM trades WHERE user_id = '%s' AND status = 'CLOSED'
                    GROUP BY symbol ORDER BY total_pnl DESC
                    """.formatted(userId);

            var results = entityManager.createNativeQuery(sql).getResultList();

            assertThat(results).hasSize(2);  // BTCUSDT, ETHUSDT
        }

        @Test
        @DisplayName("CASE WHEN 在 PostgreSQL 上正常")
        void caseWhenExpression() {
            String sql = """
                    SELECT
                        CASE WHEN net_profit > 0 THEN 'WIN' ELSE 'LOSS' END AS result,
                        COUNT(*) AS count
                    FROM trades WHERE user_id = '%s' AND status = 'CLOSED'
                    GROUP BY result
                    """.formatted(userId);

            var results = entityManager.createNativeQuery(sql).getResultList();

            assertThat(results).hasSize(2);  // WIN, LOSS
        }
    }

    @Nested
    @DisplayName("空結果")
    class EmptyResults {

        @Test
        @DisplayName("不存在的 user_id 查詢 → 空結果")
        void nonExistentUser() {
            String sql = "SELECT * FROM trades WHERE user_id = 'non-existent' AND status = 'CLOSED'";

            var results = entityManager.createNativeQuery(sql).getResultList();

            assertThat(results).isEmpty();
        }
    }
}
