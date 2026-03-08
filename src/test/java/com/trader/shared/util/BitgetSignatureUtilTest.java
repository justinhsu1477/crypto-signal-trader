package com.trader.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BitgetSignatureUtil 單元測試
 *
 * 驗證：
 * - Base64(HMAC-SHA256) 簽名格式正確
 * - GET 請求簽名（含 queryString）
 * - POST 請求簽名（含 JSON body）
 * - 空 query/body 簽名
 * - WebSocket 認證簽名
 * - 相同輸入一致性
 * - 不同 timestamp 產生不同簽名
 */
class BitgetSignatureUtilTest {

    private static final String SECRET_KEY = "mySecretKey";
    private static final String TIMESTAMP = "1672531200000";

    @Test
    @DisplayName("GET 請求：Base64 簽名格式正確（非 Hex）")
    void getRequest_producesBase64Signature() {
        String signature = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/account/accounts",
                "?productType=USDT-FUTURES", "", SECRET_KEY);

        // Base64 只包含 [A-Za-z0-9+/=]
        assertThat(signature).matches("[A-Za-z0-9+/=]+");
        // Base64(SHA256) = 44 字元（含 padding）
        assertThat(signature).hasSizeBetween(40, 48);
    }

    @Test
    @DisplayName("POST 請求：JSON body 參與簽名")
    void postRequest_withJsonBody() {
        String body = "{\"symbol\":\"BTCUSDT\",\"productType\":\"USDT-FUTURES\",\"side\":\"buy\"}";
        String signature = BitgetSignatureUtil.sign(
                TIMESTAMP, "POST", "/api/v2/mix/order/place-order",
                "", body, SECRET_KEY);

        assertThat(signature).matches("[A-Za-z0-9+/=]+");
        assertThat(signature).isNotBlank();
    }

    @Test
    @DisplayName("空 query 和 body：僅 timestamp + method + path 參與簽名")
    void emptyQueryAndBody() {
        String signature = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/market/symbol-price",
                "", "", SECRET_KEY);

        assertThat(signature).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    @DisplayName("null query 和 body：不會 NPE")
    void nullQueryAndBody_handledGracefully() {
        String signature = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/market/symbol-price",
                null, null, SECRET_KEY);

        assertThat(signature).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    @DisplayName("method 自動轉大寫：get 和 GET 產生相同簽名")
    void method_caseInsensitive() {
        String sig1 = BitgetSignatureUtil.sign(
                TIMESTAMP, "get", "/api/v2/mix/account/accounts", "", "", SECRET_KEY);
        String sig2 = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/account/accounts", "", "", SECRET_KEY);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("一致性：相同輸入永遠產生相同簽名")
    void sameInputs_produceSameOutput() {
        String sig1 = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/position/all-position",
                "?productType=USDT-FUTURES", "", SECRET_KEY);
        String sig2 = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/v2/mix/position/all-position",
                "?productType=USDT-FUTURES", "", SECRET_KEY);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("不同 timestamp 產生不同簽名")
    void differentTimestamps_produceDifferentSignatures() {
        String sig1 = BitgetSignatureUtil.sign(
                "1672531200000", "GET", "/api/v2/mix/account/accounts", "", "", SECRET_KEY);
        String sig2 = BitgetSignatureUtil.sign(
                "1672531200001", "GET", "/api/v2/mix/account/accounts", "", "", SECRET_KEY);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("WebSocket 認證簽名：使用 GET /user/verify")
    void webSocketSignature_usesCorrectPrehash() {
        String timestampSeconds = "1672531200";
        String wsSig = BitgetSignatureUtil.signWebSocket(timestampSeconds, SECRET_KEY);

        // 應與直接呼叫 sign 等價
        String manualSig = BitgetSignatureUtil.sign(
                timestampSeconds, "GET", "/user/verify", "", "", SECRET_KEY);

        assertThat(wsSig).isEqualTo(manualSig);
        assertThat(wsSig).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    @DisplayName("與 Bybit 簽名不同格式：Base64 vs Hex")
    void bitgetSignature_isDifferentFromBybitFormat() {
        String bitgetSig = BitgetSignatureUtil.sign(
                TIMESTAMP, "GET", "/api/test", "", "", SECRET_KEY);

        // Bitget 是 Base64（含 +/= 等字元），不是純 Hex
        // Hex 只有 [0-9a-f] 且長度 64
        // Base64(32 bytes) 長度約 44
        assertThat(bitgetSig).hasSizeLessThan(64);
    }
}
