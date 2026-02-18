package com.trader.shared.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Binance API 簽名工具
 * Binance 要求所有帶有 SIGNED 的端點都需要 HMAC SHA256 簽名
 */
public class BinanceSignatureUtil {

    private BinanceSignatureUtil() {}

    /**
     * 使用 HMAC SHA256 對查詢字串簽名
     *
     * @param data      要簽名的查詢字串
     * @param secretKey Binance Secret Key
     * @return 簽名後的 hex 字串
     */
    public static String sign(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }
    }
}
