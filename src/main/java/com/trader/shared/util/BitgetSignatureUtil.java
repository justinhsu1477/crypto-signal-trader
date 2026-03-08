package com.trader.shared.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Bitget V2 API 簽名工具
 *
 * Bitget 的簽名方式與 Binance/Bybit 不同：
 * - prehash = timestamp + method.toUpperCase() + requestPath + [queryString] + [body]
 * - signature = Base64(HMAC-SHA256(secretKey, prehash))
 * - 結果放在 ACCESS-SIGN header（Base64 編碼，非 Hex）
 *
 * @see <a href="https://www.bitget.com/api-doc/common/signature">Bitget API Signature</a>
 */
public class BitgetSignatureUtil {

    private BitgetSignatureUtil() {}

    /**
     * 使用 HMAC SHA256 對 Bitget V2 請求簽名
     *
     * prehash = timestamp + METHOD + requestPath + queryString + body
     *
     * 對於 GET 請求：body 為空字串，queryString 含 "?" 前綴（如果有參數）
     * 對於 POST 請求：queryString 為空字串，body 為 JSON body
     *
     * @param timestamp    毫秒級時間戳字串
     * @param method       HTTP 方法（GET / POST），內部會自動轉大寫
     * @param requestPath  API 路徑（如 "/api/v2/mix/order/place-order"）
     * @param queryString  GET 參數（含 "?" 前綴），無參數時傳空字串
     * @param body         POST JSON body，GET 時傳空字串
     * @param secretKey    Bitget Secret Key
     * @return Base64 編碼的簽名字串
     */
    public static String sign(String timestamp, String method, String requestPath,
                              String queryString, String body, String secretKey) {
        String prehash = timestamp + method.toUpperCase() + requestPath
                + (queryString != null ? queryString : "")
                + (body != null ? body : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign Bitget request", e);
        }
    }

    /**
     * 產生 WebSocket 認證簽名
     *
     * Bitget WS 認證 prehash = timestamp + "GET" + "/user/verify"
     * ⚠️ WS 的 timestamp 用「秒」（不是毫秒）
     *
     * @param timestampSeconds 秒級時間戳字串
     * @param secretKey        Bitget Secret Key
     * @return Base64 編碼的簽名字串
     */
    public static String signWebSocket(String timestampSeconds, String secretKey) {
        return sign(timestampSeconds, "GET", "/user/verify", "", "", secretKey);
    }
}
