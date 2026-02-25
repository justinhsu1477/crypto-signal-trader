package com.trader.subscription.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.subscription.config.CryptoPaymentConfig;
import com.trader.subscription.service.TronService.VerificationResult;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TronService 單元測試
 *
 * 覆蓋：輸入驗證、TronGrid API 回應解析、金額精度、收款地址、完整成功場景、異常處理
 */
class TronServiceTest {

    private static final String MY_WALLET = "TLgjqVzQbR5MvMrheRJxGH1WzXKmC7sN5a";
    private static final String SENDER_ADDR = "TSenderAddr123456789abcdef";
    private static final String TX_HASH = "abc123def456789";
    private static final String USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private CryptoPaymentConfig config;
    private ObjectMapper objectMapper;
    private HttpClient mockHttpClient;
    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse = mock(HttpResponse.class);
    private TronService service;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(CryptoPaymentConfig.class);
        objectMapper = new ObjectMapper();
        mockHttpClient = mock(HttpClient.class);

        when(config.getWalletAddress()).thenReturn(MY_WALLET);
        when(config.getTrongridApiKey()).thenReturn("");

        service = new TronService(config, objectMapper);
        injectMockHttpClient();
    }

    /**
     * 用反射將 inline 初始化的 httpClient 替換為 mock
     */
    private void injectMockHttpClient() throws Exception {
        Field field = TronService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, mockHttpClient);
    }

    /**
     * 建構 TronGrid API 回傳的 JSON（使用 trc20TransferInfo 格式）
     */
    private String buildTronGridJson(String contractRet, String toAddress, String value) {
        return String.format("""
                {
                  "data": [{
                    "ret": [{"contractRet": "%s"}],
                    "trc20TransferInfo": [{
                      "contract_address": "%s",
                      "from": "%s",
                      "to": "%s",
                      "value": "%s"
                    }]
                  }],
                  "success": true
                }""", contractRet, USDT_CONTRACT, SENDER_ADDR, toAddress, value);
    }

    /**
     * 建構帶 amount_str 欄位的 JSON（用於 fallback 測試）
     */
    private String buildTronGridJsonWithAmountStr(String contractRet, String toAddress,
                                                   String value, String amountStr) {
        String valueField = (value != null) ? String.format("\"value\": \"%s\",", value) : "";
        String amountStrField = (amountStr != null) ? String.format("\"amount_str\": \"%s\",", amountStr) : "";
        return String.format("""
                {
                  "data": [{
                    "ret": [{"contractRet": "%s"}],
                    "trc20TransferInfo": [{
                      "contract_address": "%s",
                      "from": "%s",
                      "to": "%s",
                      %s
                      %s
                      "type": "Transfer"
                    }]
                  }],
                  "success": true
                }""", contractRet, USDT_CONTRACT, SENDER_ADDR, toAddress, valueField, amountStrField);
    }

    @SuppressWarnings("unchecked")
    private void stubHttpResponse(int statusCode, String body) throws Exception {
        mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(statusCode);
        when(mockResponse.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
    }

    // ==================== 輸入驗證 ====================

    @Nested
    @DisplayName("輸入驗證")
    class InputValidation {

        @Test
        @DisplayName("txHash 為 null 時回傳失敗")
        void nullTxHash_returnsFail() {
            VerificationResult result = service.verifyTransaction(null, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易 Hash 不可為空");
            assertThat(result.amount()).isNull();
            assertThat(result.fromAddress()).isNull();
            assertThat(result.toAddress()).isNull();
        }

        @Test
        @DisplayName("txHash 為空白字串時回傳失敗")
        void blankTxHash_returnsFail() {
            VerificationResult result = service.verifyTransaction("   ", BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易 Hash 不可為空");
        }

        @Test
        @DisplayName("系統收款地址為 null 時回傳失敗")
        void nullWalletAddress_returnsFail() {
            when(config.getWalletAddress()).thenReturn(null);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("系統收款地址未設定，請聯繫管理員");
        }

        @Test
        @DisplayName("系統收款地址為空字串時回傳失敗")
        void blankWalletAddress_returnsFail() {
            when(config.getWalletAddress()).thenReturn("");

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("系統收款地址未設定，請聯繫管理員");
        }
    }

    // ==================== TronGrid API 回應解析 ====================

    @Nested
    @DisplayName("TronGrid API 回應解析")
    class TronGridApiParsing {

        @Test
        @DisplayName("HTTP 200 但 success=false 時回傳交易不存在")
        void http200ButSuccessFalse_returnsFail() throws Exception {
            String json = """
                    {"data": [], "success": false}""";
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易不存在或尚未上鏈，請稍後再試");
        }

        @Test
        @DisplayName("HTTP 404 回傳交易不存在")
        void http404_returnsFail() throws Exception {
            stubHttpResponse(404, "Not Found");

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易不存在或尚未上鏈，請稍後再試");
        }

        @Test
        @DisplayName("contractRet 為 REVERT 時回傳交易執行失敗")
        void contractRetRevert_returnsFail() throws Exception {
            String json = buildTronGridJson("REVERT", MY_WALLET, "19000000");
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易執行失敗，狀態: REVERT");
        }

        @Test
        @DisplayName("無 USDT 轉帳記錄時回傳未找到")
        void noUsdtTransferLog_returnsFail() throws Exception {
            // 沒有 trc20TransferInfo、沒有 token_info、沒有 log
            String json = """
                    {
                      "data": [{
                        "ret": [{"contractRet": "SUCCESS"}]
                      }],
                      "success": true
                    }""";
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("交易中未找到 USDT TRC20 轉帳記錄");
        }
    }

    // ==================== 金額精度驗證 ====================

    @Nested
    @DisplayName("金額精度驗證")
    class AmountPrecision {

        @Test
        @DisplayName("19 USDT (value=19000000) 恰好等於預期金額 → 通過")
        void exact19Usdt_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("19.000000"));
        }

        @Test
        @DisplayName("49 USDT (value=49000000) 恰好等於預期金額 → 通過")
        void exact49Usdt_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "49000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(49));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("49.000000"));
        }

        @Test
        @DisplayName("18.999999 USDT (value=18999999) 小於 19 → 金額不足")
        void slightlyUnder19_fails() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "18999999"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("金額不足");
            assertThat(result.message()).contains("19");
            assertThat(result.message()).contains("18.999999");
        }

        @Test
        @DisplayName("20 USDT 超額付款 → 通過")
        void overpayment20Usdt_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "20000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("20.000000"));
        }

        @Test
        @DisplayName("金額為 0 → 金額不足")
        void zeroAmount_fails() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "0"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("金額不足");
        }

        @Test
        @DisplayName("100K USDT (value=100000000000) 大金額不溢位 → 通過")
        void largeAmount100K_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "100000000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("100000.000000"));
        }

        @Test
        @DisplayName("amount_str fallback: value 缺失但 amount_str=19000000 → 通過")
        void amountStrFallback_passes() throws Exception {
            String json = buildTronGridJsonWithAmountStr("SUCCESS", MY_WALLET, null, "19000000");
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("19.000000"));
        }

        @Test
        @DisplayName("value 和 amount_str 都缺失 → 金額為 0 → 失敗")
        void bothMissing_fails() throws Exception {
            String json = buildTronGridJsonWithAmountStr("SUCCESS", MY_WALLET, null, null);
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("金額不足");
        }

        @Test
        @DisplayName("value 為非數字字串但 amount_str 有效 → 使用 amount_str → 通過")
        void invalidValueFallbackToAmountStr_passes() throws Exception {
            String json = buildTronGridJsonWithAmountStr("SUCCESS", MY_WALLET, "invalid", "19000000");
            stubHttpResponse(200, json);

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("19.000000"));
        }

        @Test
        @DisplayName("BigDecimal scale 獨立比較: valueOf(19.0) vs 19.000000 → 相等")
        void scaleIndependentComparison_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000000"));

            // BigDecimal.valueOf(19.0) has scale=1, extractAmount returns scale=6
            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19.0));

            assertThat(result.success()).isTrue();
            // compareTo ignores scale
            assertThat(result.amount().compareTo(BigDecimal.valueOf(19.0))).isZero();
        }

        @Test
        @DisplayName("49.99 USDT (value=49990000) 精確匹配 → 通過")
        void exact4999_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "49990000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(49.99));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("49.990000"));
        }

        @Test
        @DisplayName("RoundingMode.DOWN: 19000001 → 19.000001 不進位")
        void roundingDown_noRoundUp() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000001"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("19.000001"));
            // 確認不會被進位到 19.000002
            assertThat(result.amount().toPlainString()).isEqualTo("19.000001");
        }
    }

    // ==================== 收款地址驗證 ====================

    @Nested
    @DisplayName("收款地址驗證")
    class AddressValidation {

        @Test
        @DisplayName("地址完全匹配 → 通過")
        void exactMatch_passes() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.toAddress()).isEqualTo(MY_WALLET);
        }

        @Test
        @DisplayName("地址大小寫不同 (equalsIgnoreCase) → 通過")
        void caseInsensitiveMatch_passes() throws Exception {
            String lowerCaseWallet = MY_WALLET.toLowerCase();
            stubHttpResponse(200, buildTronGridJson("SUCCESS", lowerCaseWallet, "19000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.toAddress()).isEqualTo(lowerCaseWallet);
        }

        @Test
        @DisplayName("地址不同 → 收款地址不正確")
        void differentAddress_fails() throws Exception {
            String wrongAddress = "TWrongAddress999888777666555";
            stubHttpResponse(200, buildTronGridJson("SUCCESS", wrongAddress, "19000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("收款地址不正確");
        }
    }

    // ==================== 完整成功場景 ====================

    @Nested
    @DisplayName("完整成功場景")
    class FullSuccessScenario {

        @Test
        @DisplayName("完整有效 JSON → 回傳 ok 含正確 amount/from/to")
        void fullValidJson_returnsOk() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "49000000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(49));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("驗證通過");
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("49"));
            assertThat(result.fromAddress()).isEqualTo(SENDER_ADDR);
            assertThat(result.toAddress()).isEqualTo(MY_WALLET);
        }

        @Test
        @DisplayName("驗證結果各欄位型別與值正確")
        void verifyResultFieldValues() throws Exception {
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19500000"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("驗證通過");
            assertThat(result.amount()).isNotNull();
            assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("19.5"));
            assertThat(result.fromAddress()).isNotBlank();
            assertThat(result.toAddress()).isNotBlank();
            // 確認 from 和 to 不是同一個地址
            assertThat(result.fromAddress()).isNotEqualTo(result.toAddress());
        }
    }

    // ==================== 異常處理 ====================

    @Nested
    @DisplayName("異常處理")
    class ExceptionHandling {

        @Test
        @DisplayName("HttpClient 拋出 IOException → 鏈上驗證失敗")
        @SuppressWarnings("unchecked")
        void httpClientThrowsIOException_returnsFail() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("鏈上驗證失敗，請稍後再試");
        }

        @Test
        @DisplayName("回傳格式錯誤的 JSON → 鏈上驗證失敗")
        @SuppressWarnings("unchecked")
        void malformedJson_returnsFail() throws Exception {
            stubHttpResponse(200, "{not valid json!!!}}}");

            VerificationResult result = service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo("鏈上驗證失敗，請稍後再試");
        }

        @Test
        @DisplayName("設有 API Key 時驗證 TRON-PRO-API-KEY header 被加入")
        @SuppressWarnings("unchecked")
        void apiKeySet_headerAdded() throws Exception {
            when(config.getTrongridApiKey()).thenReturn("my-trongrid-api-key");
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000000"));

            service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));

            HttpRequest captured = captor.getValue();
            assertThat(captured.headers().firstValue("TRON-PRO-API-KEY"))
                    .isPresent()
                    .hasValue("my-trongrid-api-key");
        }

        @Test
        @DisplayName("API Key 為空白時不加入 header")
        @SuppressWarnings("unchecked")
        void apiKeyBlank_noHeader() throws Exception {
            when(config.getTrongridApiKey()).thenReturn("  ");
            stubHttpResponse(200, buildTronGridJson("SUCCESS", MY_WALLET, "19000000"));

            service.verifyTransaction(TX_HASH, BigDecimal.valueOf(19));

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));

            HttpRequest captured = captor.getValue();
            assertThat(captured.headers().firstValue("TRON-PRO-API-KEY")).isEmpty();
        }
    }
}
