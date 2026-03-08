package com.trader.trading.service;

import com.trader.shared.config.BitgetConfig;
import com.trader.shared.util.BitgetSignatureUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Bitget V2 Private WebSocket Stream Provider
 *
 * 連線機制（類似 Bybit，與 Binance 不同）：
 * 1. 直接連線 WS URL = wsBaseUrl + /v2/ws/private
 * 2. onOpen 後發送 login message（HMAC-SHA256 Base64 簽名 + passphrase）
 * 3. login 成功後 subscribe orders + positions channels
 * 4. Bitget WS 內建 ping/pong，keepAlive 為 no-op
 * 5. 斷線清理為 no-op（無 listenKey 概念）
 *
 * Auth 簽名：Base64(HMAC-SHA256(secretKey, timestamp + "GET" + "/user/verify"))
 * ⚠️ WS timestamp 用「秒」不是「毫秒」
 */
@Slf4j
@Component
@ConditionalOnExpression("'${exchanges.enabled:BINANCE}'.toUpperCase().contains('BITGET')")
public class BitgetStreamProvider implements ExchangeStreamProvider {

    private final BitgetConfig bitgetConfig;

    public BitgetStreamProvider(BitgetConfig bitgetConfig) {
        this.bitgetConfig = bitgetConfig;
    }

    @Override
    public ConnectResult connect(String apiKey, String secretKey,
                                  OkHttpClient wsClient, WebSocketListener listener) {
        String wsUrl = bitgetConfig.getWsBaseUrl() + "/v2/ws/private";
        Request request = new Request.Builder().url(wsUrl).build();
        WebSocket ws = wsClient.newWebSocket(request, listener);
        // auth + subscribe 在 PerUserWebSocketListener.onOpen 中處理
        return new ConnectResult(ws, null);
    }

    @Override
    public int keepAlive(String apiKey, String connectionContext) {
        // Bitget WS 內建 ping/pong，不需手動 keepAlive
        return 200;
    }

    @Override
    public void cleanup(String apiKey, String connectionContext) {
        // Bitget 無 listenKey 概念，不需清理
    }

    @Override
    public String getExchangeName() {
        return "BITGET";
    }

    /**
     * 產生 Bitget WebSocket 認證訊息
     *
     * Bitget WS login 格式：
     * {"op":"login","args":[{"apiKey":"xxx","passphrase":"xxx","timestamp":"seconds","sign":"xxx"}]}
     *
     * sign = Base64(HMAC-SHA256(secretKey, timestamp + "GET" + "/user/verify"))
     * ⚠️ timestamp 用「秒」不是「毫秒」
     *
     * @param apiKey     API Key
     * @param secretKey  Secret Key
     * @param passphrase Passphrase（Bitget 必填）
     * @return JSON login message string
     */
    public static String buildAuthMessage(String apiKey, String secretKey, String passphrase) {
        String timestampSeconds = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = BitgetSignatureUtil.signWebSocket(timestampSeconds, secretKey);
        return String.format(
                "{\"op\":\"login\",\"args\":[{\"apiKey\":\"%s\",\"passphrase\":\"%s\",\"timestamp\":\"%s\",\"sign\":\"%s\"}]}",
                apiKey, passphrase, timestampSeconds, signature);
    }

    /**
     * 產生 Bitget WebSocket 訂閱訊息
     * 訂閱 orders（訂單變動）和 positions（倉位變動）
     *
     * Bitget V2 subscribe 格式：
     * {"op":"subscribe","args":[
     *   {"instType":"USDT-FUTURES","channel":"orders","instId":"default"},
     *   {"instType":"USDT-FUTURES","channel":"positions","instId":"default"}
     * ]}
     */
    public static String buildSubscribeMessage() {
        return "{\"op\":\"subscribe\",\"args\":["
                + "{\"instType\":\"USDT-FUTURES\",\"channel\":\"orders\",\"instId\":\"default\"},"
                + "{\"instType\":\"USDT-FUTURES\",\"channel\":\"positions\",\"instId\":\"default\"}"
                + "]}";
    }
}
