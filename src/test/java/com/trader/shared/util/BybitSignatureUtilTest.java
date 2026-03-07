package com.trader.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BybitSignatureUtil 單元測試
 *
 * 驗證：
 * - 已知輸入/輸出的 HMAC-SHA256 簽名正確性
 * - 空 payload 簽名
 * - POST JSON body 簽名
 * - GET query string 簽名
 * - 相同輸入一致性
 * - 不同 timestamp 產生不同簽名
 */
class BybitSignatureUtilTest {

    private static final String API_KEY = "myApiKey";
    private static final String SECRET_KEY = "mySecretKey";
    private static final String TIMESTAMP = "1672531200000";
    private static final String RECV_WINDOW = "5000";

    @Test
    @DisplayName("已知輸入/輸出：HMAC-SHA256 產生預期結果")
    void knownInputProducesExpectedOutput() {
        String payload = "symbol=BTCUSDT&category=linear";

        String signature = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, payload, SECRET_KEY);

        // signPayload = "1672531200000myApiKey5000symbol=BTCUSDT&category=linear"
        // HMAC-SHA256 with key "mySecretKey"
        assertThat(signature)
                .isEqualTo("df3e4b57dc26166afb523050a599edf6960bd8501ca7cdc2359ed4554ee1e7a0");
    }

    @Test
    @DisplayName("空 payload：簽名僅包含 timestamp + apiKey + recvWindow")
    void emptyPayloadProducesValidSignature() {
        String signature = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, "", SECRET_KEY);

        // signPayload = "1672531200000myApiKey5000"
        assertThat(signature)
                .isEqualTo("4478930b21259c07e36fe60df0346ae96ed86df922f58071521a48ae34029e28");
        assertThat(signature).hasSize(64); // SHA256 hex = 64 chars
    }

    @Test
    @DisplayName("POST JSON body：正確簽名 JSON 字串")
    void postWithJsonBody() {
        String jsonBody = "{\"symbol\":\"BTCUSDT\",\"side\":\"Buy\",\"orderType\":\"Market\",\"qty\":\"0.01\"}";

        String signature = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, jsonBody, SECRET_KEY);

        assertThat(signature)
                .isEqualTo("43c19fa7cc5a00223c95d0726a4cc3013cb150eba781019e1c2bc66de26dae79");
    }

    @Test
    @DisplayName("GET query string：正確簽名查詢字串")
    void getWithQueryString() {
        String queryString = "symbol=BTCUSDT&category=linear";

        String signature = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, queryString, SECRET_KEY);

        assertThat(signature).isNotBlank();
        assertThat(signature).hasSize(64);
        // 與 knownInputProducesExpectedOutput 一致
        assertThat(signature)
                .isEqualTo("df3e4b57dc26166afb523050a599edf6960bd8501ca7cdc2359ed4554ee1e7a0");
    }

    @Test
    @DisplayName("一致性：相同輸入永遠產生相同簽名")
    void sameInputsProduceSameOutput() {
        String payload = "symbol=ETHUSDT&category=linear";

        String signature1 = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, payload, SECRET_KEY);
        String signature2 = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, payload, SECRET_KEY);
        String signature3 = BybitSignatureUtil.sign(TIMESTAMP, API_KEY, RECV_WINDOW, payload, SECRET_KEY);

        assertThat(signature1).isEqualTo(signature2);
        assertThat(signature2).isEqualTo(signature3);
    }

    @Test
    @DisplayName("不同 timestamp 產生不同簽名")
    void differentTimestampsProduceDifferentSignatures() {
        String payload = "symbol=BTCUSDT&category=linear";

        String signature1 = BybitSignatureUtil.sign("1672531200000", API_KEY, RECV_WINDOW, payload, SECRET_KEY);
        String signature2 = BybitSignatureUtil.sign("1672531200001", API_KEY, RECV_WINDOW, payload, SECRET_KEY);

        assertThat(signature1).isNotEqualTo(signature2);
    }
}
