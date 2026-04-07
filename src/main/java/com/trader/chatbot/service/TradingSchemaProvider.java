package com.trader.chatbot.service;

import org.springframework.stereotype.Component;

/**
 * 提供交易資料庫的 schema context 和 few-shot SQL 範例，
 * 供 TradingNlqService 建構 SQL 生成 prompt。
 *
 * 設計參考 GenBI 的 PromptServiceImpl：
 * - 只暴露非敏感欄位（排除 order ID、signal hash、JSON blob 等）
 * - 附帶中文欄位說明，幫助 LLM 理解業務語義
 * - Few-shot 範例遵循 GenBI 的 RAG 格式（Q: ... SQL: ...）
 */
@Component
public class TradingSchemaProvider {

    private static final String SCHEMA_CONTEXT = """
        -- Table: trades（交易紀錄 — 一筆「開倉→平倉」= 一筆紀錄）
        CREATE TABLE trades (
            trade_id        TEXT PRIMARY KEY,
            user_id         TEXT NOT NULL,          -- 用戶 ID
            symbol          TEXT NOT NULL,           -- 交易對，例如 BTCUSDT
            side            TEXT NOT NULL,           -- 方向：LONG 或 SHORT
            entry_price     DOUBLE PRECISION,        -- 入場價
            entry_quantity  DOUBLE PRECISION,        -- 入場數量
            entry_time      TIMESTAMP,               -- 開倉時間
            exit_price      DOUBLE PRECISION,        -- 出場價
            exit_quantity   DOUBLE PRECISION,        -- 出場數量
            exit_time       TIMESTAMP,               -- 平倉時間
            stop_loss       DOUBLE PRECISION,        -- 止損價
            leverage        INTEGER,                 -- 槓桿倍數
            gross_profit    DOUBLE PRECISION,        -- 毛利
            commission      DOUBLE PRECISION,        -- 總手續費
            net_profit      DOUBLE PRECISION,        -- 淨利 = 毛利 - 手續費
            status          TEXT,                    -- OPEN=持倉中, CLOSED=已平倉, CANCELLED=已取消
            exit_reason     TEXT,                    -- 出場原因：STOP_LOSS / SIGNAL_CLOSE / MANUAL_CLOSE / FAIL_SAFE
            ai_confidence   INTEGER,                 -- AI 信心分數 0-100
            source_author_name TEXT,                 -- 訊號來源分析師名稱
            dca_count       INTEGER,                 -- 補倉次數（0=首次入場）
            simulated       BOOLEAN,                 -- true=模擬交易
            created_at      TIMESTAMP
        );

        -- Table: broadcast_logs（廣播跟單紀錄 — 每次訊號廣播的執行結果）
        CREATE TABLE broadcast_logs (
            id              BIGSERIAL PRIMARY KEY,
            signal_action   TEXT,                    -- ENTRY, CLOSE, MOVE_SL, CANCEL
            symbol          TEXT,                    -- 交易對
            side            TEXT,                    -- LONG / SHORT
            entry_price     DOUBLE PRECISION,
            stop_loss       DOUBLE PRECISION,
            source_author   TEXT,                    -- 訊號來源分析師
            total_users     INTEGER,                 -- 廣播目標用戶數
            success_count   INTEGER,                 -- 成功人數
            fail_count      INTEGER,                 -- 失敗人數
            skipped_no_sub  INTEGER,                 -- 跳過：無訂閱
            skipped_no_key  INTEGER,                 -- 跳過：無 API Key
            ai_confidence   INTEGER,                 -- AI 信心分數
            status          TEXT,                    -- COMPLETED, INTERRUPTED
            created_at      TIMESTAMP
        );
        """;

    private static final String FEW_SHOT_USER_TEMPLATE = """
        以下是常見查詢的 SQL 範例供參考：

        Q: 我這個月賺了多少？
        SQL: SELECT SUM(net_profit) AS total_pnl, COUNT(*) AS trade_count FROM trades WHERE user_id = '{USER_ID}' AND status = 'CLOSED' AND exit_time >= date_trunc('month', NOW())

        Q: 我哪個幣種表現最好？
        SQL: SELECT symbol, SUM(net_profit) AS total_pnl, COUNT(*) AS trade_count, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate FROM trades WHERE user_id = '{USER_ID}' AND status = 'CLOSED' GROUP BY symbol ORDER BY total_pnl DESC

        Q: 我最近 10 筆交易
        SQL: SELECT symbol, side, entry_price, exit_price, net_profit, exit_reason, exit_time FROM trades WHERE user_id = '{USER_ID}' AND status = 'CLOSED' ORDER BY exit_time DESC LIMIT 10

        Q: 我的勝率多少？
        SQL: SELECT COUNT(*) AS total, SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) AS wins, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate FROM trades WHERE user_id = '{USER_ID}' AND status = 'CLOSED'

        Q: 我做多和做空哪個比較好？
        SQL: SELECT side, COUNT(*) AS trades, SUM(net_profit) AS total_pnl, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate FROM trades WHERE user_id = '{USER_ID}' AND status = 'CLOSED' GROUP BY side
        """;

    private static final String FEW_SHOT_ADMIN = """
        以下是常見查詢的 SQL 範例供參考：

        Q: 所有用戶的損益排名
        SQL: SELECT user_id, SUM(net_profit) AS total_pnl, COUNT(*) AS trade_count, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate FROM trades WHERE status = 'CLOSED' GROUP BY user_id ORDER BY total_pnl DESC

        Q: 哪個訊號來源表現最好？
        SQL: SELECT source_author_name, COUNT(*) AS trades, SUM(net_profit) AS total_pnl, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate FROM trades WHERE status = 'CLOSED' AND source_author_name IS NOT NULL GROUP BY source_author_name ORDER BY total_pnl DESC

        Q: 今天的廣播執行狀況
        SQL: SELECT symbol, signal_action, side, source_author, success_count, fail_count, ai_confidence, created_at FROM broadcast_logs WHERE created_at >= CURRENT_DATE ORDER BY created_at DESC

        Q: 最近一週每天的總損益
        SQL: SELECT DATE(exit_time) AS trade_date, COUNT(*) AS trades, SUM(net_profit) AS daily_pnl FROM trades WHERE status = 'CLOSED' AND exit_time >= NOW() - INTERVAL '7 days' GROUP BY DATE(exit_time) ORDER BY trade_date

        Q: AI 信心分數高的交易勝率比較好嗎？
        SQL: SELECT CASE WHEN ai_confidence >= 70 THEN 'High (>=70)' WHEN ai_confidence >= 40 THEN 'Medium (40-69)' ELSE 'Low (<40)' END AS confidence_tier, COUNT(*) AS trades, ROUND(100.0 * SUM(CASE WHEN net_profit > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_rate, SUM(net_profit) AS total_pnl FROM trades WHERE status = 'CLOSED' AND ai_confidence IS NOT NULL GROUP BY confidence_tier ORDER BY confidence_tier
        """;

    public String getSchemaContext() {
        return SCHEMA_CONTEXT;
    }

    public String getFewShotExamples(String userId, boolean isAdmin) {
        if (isAdmin) {
            return FEW_SHOT_ADMIN;
        }
        return FEW_SHOT_USER_TEMPLATE.replace("{USER_ID}", userId);
    }
}
