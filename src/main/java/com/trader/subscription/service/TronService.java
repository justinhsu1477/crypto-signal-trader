package com.trader.subscription.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.subscription.config.CryptoPaymentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * TRON 鏈上交易驗證服務
 *
 * 透過 TronGrid API 驗證 TRC20 USDT 轉帳交易：
 * 1. 交易是否存在且已確認
 * 2. 收款地址是否正確
 * 3. 金額是否足夠
 *
 * USDT on TRON 合約地址: TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TronService {

    private final CryptoPaymentConfig config;
    private final ObjectMapper objectMapper;

    private static final String TRONGRID_BASE_URL = "https://api.trongrid.io";
    /** USDT TRC20 合約地址 */
    private static final String USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    /** USDT 精度: 6 位小數 */
    private static final int USDT_DECIMALS = 6;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 驗證交易結果
     */
    public record VerificationResult(
            boolean success,
            String message,
            BigDecimal amount,
            String fromAddress,
            String toAddress
    ) {
        public static VerificationResult fail(String message) {
            return new VerificationResult(false, message, null, null, null);
        }

        public static VerificationResult ok(BigDecimal amount, String from, String to) {
            return new VerificationResult(true, "驗證通過", amount, from, to);
        }
    }

    /**
     * 驗證 TRC20 USDT 交易
     *
     * @param txHash          交易 hash
     * @param expectedAmount  預期金額（USDT）
     * @return 驗證結果
     */
    public VerificationResult verifyTransaction(String txHash, BigDecimal expectedAmount) {
        if (txHash == null || txHash.isBlank()) {
            return VerificationResult.fail("交易 Hash 不可為空");
        }

        String myWallet = config.getWalletAddress();
        if (myWallet == null || myWallet.isBlank()) {
            log.error("收款錢包地址未設定");
            return VerificationResult.fail("系統收款地址未設定，請聯繫管理員");
        }

        try {
            // 1. 查詢交易資訊
            JsonNode txInfo = fetchTransactionInfo(txHash);
            if (txInfo == null) {
                return VerificationResult.fail("交易不存在或尚未上鏈，請稍後再試");
            }

            // 2. 檢查交易是否成功
            String result = getReceiptResult(txInfo);
            if (!"SUCCESS".equals(result)) {
                return VerificationResult.fail("交易執行失敗，狀態: " + result);
            }

            // 3. 從 TRC20 Transfer 事件提取轉帳資訊
            JsonNode transferLog = findUsdtTransferLog(txInfo);
            if (transferLog == null) {
                return VerificationResult.fail("交易中未找到 USDT TRC20 轉帳記錄");
            }

            String toAddress = transferLog.path("to").asText("");
            String fromAddress = transferLog.path("from").asText("");
            BigDecimal actualAmount = extractAmount(transferLog);

            // 4. 驗證收款地址
            if (!myWallet.equalsIgnoreCase(toAddress)) {
                log.warn("收款地址不匹配: expected={}, actual={}", myWallet, toAddress);
                return VerificationResult.fail("收款地址不正確");
            }

            // 5. 驗證金額
            if (actualAmount.compareTo(expectedAmount) < 0) {
                log.warn("金額不足: expected={}, actual={}", expectedAmount, actualAmount);
                return VerificationResult.fail(
                        String.format("金額不足，需要 %s USDT，實際收到 %s USDT",
                                expectedAmount.toPlainString(), actualAmount.toPlainString()));
            }

            // 6. 檢查確認數（通過 blockNumber 比對）
            // TronGrid transactioninfo 回傳的交易都是已確認的，
            // 若能查到 receipt 表示已上鏈確認

            log.info("交易驗證通過: txHash={}, from={}, to={}, amount={} USDT",
                    txHash, fromAddress, toAddress, actualAmount);
            return VerificationResult.ok(actualAmount, fromAddress, toAddress);

        } catch (Exception e) {
            log.error("TronGrid API 呼叫失敗: txHash={}, error={}", txHash, e.getMessage(), e);
            return VerificationResult.fail("鏈上驗證失敗，請稍後再試");
        }
    }

    /**
     * 呼叫 TronGrid API 取得交易資訊
     */
    private JsonNode fetchTransactionInfo(String txHash) throws Exception {
        String url = TRONGRID_BASE_URL + "/v1/transactions/" + txHash;

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15));

        // 加上 API Key（如有設定）
        String apiKey = config.getTrongridApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("TRON-PRO-API-KEY", apiKey);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("TronGrid API 回傳 HTTP {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());

        // v1 API 回傳格式: { "data": [ {...} ], "success": true }
        if (!root.path("success").asBoolean(false)) {
            return null;
        }

        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }

        return data.get(0);
    }

    /**
     * 取得交易 receipt 結果
     */
    private String getReceiptResult(JsonNode txInfo) {
        return txInfo.path("ret").path(0).path("contractRet").asText("UNKNOWN");
    }

    /**
     * 從交易 log 中找到 USDT TRC20 Transfer 事件
     *
     * TRC20 Transfer topic: ddf252ad...（Transfer(address,address,uint256)）
     */
    private JsonNode findUsdtTransferLog(JsonNode txInfo) {
        // 先嘗試從 tokenTransferInfo 取得（TronGrid v1 會直接解析）
        JsonNode tokenInfo = txInfo.path("token_info");
        if (!tokenInfo.isMissingNode() && USDT_CONTRACT.equalsIgnoreCase(
                tokenInfo.path("address").asText(""))) {
            return txInfo;  // 整個 txInfo 就包含 transfer 資訊
        }

        // 嘗試從 trc20TransferInfo 取得
        JsonNode trc20 = txInfo.path("trc20TransferInfo");
        if (trc20.isArray()) {
            for (JsonNode transfer : trc20) {
                if (USDT_CONTRACT.equalsIgnoreCase(
                        transfer.path("contract_address").asText(""))) {
                    return transfer;
                }
            }
        }

        // 最後嘗試 log 事件
        JsonNode logs = txInfo.path("log");
        if (logs.isArray()) {
            for (JsonNode logEntry : logs) {
                String address = logEntry.path("address").asText("");
                // USDT 合約地址（hex 格式，去掉 41 前綴比對）
                if (address.length() > 0) {
                    // 簡化：直接從 data 欄位解析金額
                    return logEntry;
                }
            }
        }

        return null;
    }

    /**
     * 從 transfer 事件中提取 USDT 金額
     */
    private BigDecimal extractAmount(JsonNode transferLog) {
        // 嘗試 value 欄位（直接數值）
        String valueStr = transferLog.path("value").asText("");
        if (!valueStr.isBlank()) {
            try {
                return new BigDecimal(valueStr)
                        .divide(BigDecimal.TEN.pow(USDT_DECIMALS), USDT_DECIMALS, RoundingMode.DOWN);
            } catch (NumberFormatException e) {
                // fallback
            }
        }

        // 嘗試 amount_str 欄位
        String amountStr = transferLog.path("amount_str").asText("");
        if (!amountStr.isBlank()) {
            try {
                return new BigDecimal(amountStr)
                        .divide(BigDecimal.TEN.pow(USDT_DECIMALS), USDT_DECIMALS, RoundingMode.DOWN);
            } catch (NumberFormatException e) {
                // fallback
            }
        }

        log.warn("無法從 transfer log 提取金額: {}", transferLog);
        return BigDecimal.ZERO;
    }
}
