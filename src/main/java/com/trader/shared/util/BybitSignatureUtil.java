package com.trader.shared.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Bybit V5 API 簽名工具
 *
 * Bybit 的簽名方式與 Binance 不同：
 * - Binance: HMAC(queryString, secret) → 附加到 query string 的 &signature=xxx
 * - Bybit: HMAC(timestamp + apiKey + recvWindow + payload, secret) → 放在 X-BAPI-SIGN header
 *
 * @see <a href="https://bybit-exchange.github.io/docs/v5/guide#create-a-request">Bybit V5 API Authentication</a>
 */
public class BybitSignatureUtil {

    private BybitSignatureUtil() {}

    /**
     * 使用 HMAC SHA256 對 Bybit V5 請求簽名
     *
     * signPayload = timestamp + apiKey + recvWindow + payload
     * signature = HMAC-SHA256(signPayload, secretKey)
     *
     * 對於 GET 請求：payload = queryString（例如 "symbol=BTCUSDT&category=linear"）
     * 對於 POST 請求：payload = JSON body（例如 '{"symbol":"BTCUSDT","side":"Buy"}'）
     *
     * @param timestamp  毫秒級時間戳
     * @param apiKey     Bybit API Key
     * @param recvWindow 接收視窗（毫秒），通常為 5000
     * @param payload    GET 的 queryString 或 POST 的 JSON body
     * @param secretKey  Bybit Secret Key
     * @return 簽名後的 hex 字串
     */
    public static String sign(String timestamp, String apiKey, String recvWindow,
                              String payload, String secretKey) {
        String signPayload = timestamp + apiKey + recvWindow + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(signPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign Bybit request", e);
        }
    }
}
