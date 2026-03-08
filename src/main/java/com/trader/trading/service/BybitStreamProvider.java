package com.trader.trading.service;

import com.trader.shared.config.BybitConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Bybit V5 Private WebSocket Stream Provider
 *
 * 連線機制（與 Binance 不同）：
 * 1. 直接連線 WS URL = wsBaseUrl + /v5/private
 * 2. onOpen 後發送 auth message（HMAC-SHA256 簽名）
 * 3. auth 成功後 subscribe execution + position topics
 * 4. Bybit WS 內建 ping/pong，keepAlive 為 no-op
 * 5. 斷線清理為 no-op（無 listenKey 概念）
 *
 * Auth 簽名：HMAC-SHA256("GET/realtime" + expires, secretKey)
 */
@Slf4j
@Component
public class BybitStreamProvider implements ExchangeStreamProvider {

    private final BybitConfig bybitConfig;

    public BybitStreamProvider(BybitConfig bybitConfig) {
        this.bybitConfig = bybitConfig;
    }

    @Override
    public ConnectResult connect(String apiKey, String secretKey,
                                  OkHttpClient wsClient, WebSocketListener listener) {
        String wsUrl = bybitConfig.getWsBaseUrl() + "/v5/private";
        Request request = new Request.Builder().url(wsUrl).build();
        WebSocket ws = wsClient.newWebSocket(request, listener);
        // auth + subscribe 在 PerUserWebSocketListener.onOpen 中處理
        return new ConnectResult(ws, null);
    }

    @Override
    public int keepAlive(String apiKey, String connectionContext) {
        // Bybit WS 自動 ping/pong，不需手動 keepAlive
        return 200;
    }

    @Override
    public void cleanup(String apiKey, String connectionContext) {
        // Bybit 無 listenKey 概念，不需清理
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    /**
     * 產生 Bybit WebSocket 認證訊息
     *
     * @param apiKey    API Key
     * @param secretKey Secret Key
     * @return JSON auth message string
     */
    public static String buildAuthMessage(String apiKey, String secretKey) {
        long expires = System.currentTimeMillis() + 30_000; // 30 秒有效
        String signPayload = "GET/realtime" + expires;
        String signature = hmacSha256(signPayload, secretKey);
        return String.format("{\"op\":\"auth\",\"args\":[\"%s\",%d,\"%s\"]}", apiKey, expires, signature);
    }

    /**
     * 產生 Bybit WebSocket 訂閱訊息
     * 訂閱 execution（成交）和 position（倉位變動）
     */
    public static String buildSubscribeMessage() {
        return "{\"op\":\"subscribe\",\"args\":[\"execution\",\"position\"]}";
    }

    /**
     * HMAC-SHA256 簽名（Bybit WebSocket 專用）
     */
    static String hmacSha256(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 簽名失敗", e);
        }
    }
}
