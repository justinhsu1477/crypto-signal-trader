package com.trader.chatbot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TradingSchemaProvider — Schema context & few-shot SQL examples")
class TradingSchemaProviderTest {

    private final TradingSchemaProvider provider = new TradingSchemaProvider();

    private static final String USER_ID = "test-user-001";

    @Test
    @DisplayName("schema context 包含 trades 和 broadcast_logs 表")
    void schemaContextContainsBothTables() {
        String schema = provider.getSchemaContext();
        assertThat(schema).contains("trades");
        assertThat(schema).contains("broadcast_logs");
    }

    @Test
    @DisplayName("schema context 不暴露敏感欄位（order ID, signal hash）")
    void schemaContextExcludesSensitiveFields() {
        String schema = provider.getSchemaContext();
        assertThat(schema).doesNotContain("entry_order_id");
        assertThat(schema).doesNotContain("exit_order_id");
        assertThat(schema).doesNotContain("signal_hash");
        assertThat(schema).doesNotContain("take_profits");
        assertThat(schema).doesNotContain("user_results");
    }

    @Test
    @DisplayName("schema context 包含核心欄位")
    void schemaContextContainsCoreFields() {
        String schema = provider.getSchemaContext();
        assertThat(schema).contains("user_id");
        assertThat(schema).contains("symbol");
        assertThat(schema).contains("net_profit");
        assertThat(schema).contains("status");
        assertThat(schema).contains("exit_reason");
    }

    @Test
    @DisplayName("一般用戶 few-shot 範例包含 userId 替換")
    void userFewShotContainsUserId() {
        String examples = provider.getFewShotExamples(USER_ID, false);
        assertThat(examples).contains("user_id = '" + USER_ID + "'");
        assertThat(examples).doesNotContain("{USER_ID}");
    }

    @Test
    @DisplayName("一般用戶 few-shot 不包含跨用戶查詢")
    void userFewShotNosCrossUserQueries() {
        String examples = provider.getFewShotExamples(USER_ID, false);
        assertThat(examples).doesNotContain("所有用戶");
        assertThat(examples).doesNotContain("GROUP BY user_id");
    }

    @Test
    @DisplayName("Admin few-shot 包含跨用戶查詢")
    void adminFewShotContainsCrossUserQueries() {
        String examples = provider.getFewShotExamples(USER_ID, true);
        assertThat(examples).contains("所有用戶");
        assertThat(examples).contains("broadcast_logs");
    }

    @Test
    @DisplayName("Admin few-shot 不包含 userId 硬編碼")
    void adminFewShotNoUserIdHardcoded() {
        String examples = provider.getFewShotExamples(USER_ID, true);
        assertThat(examples).doesNotContain(USER_ID);
    }
}
