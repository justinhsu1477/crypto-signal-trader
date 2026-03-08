package com.trader.trading.service;

import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 交易所 User Data Stream 抽象介面
 *
 * 不同交易所的 WebSocket 連線機制不同：
 * - Binance: 先 POST 建立 listenKey → WS URL = baseUrl + listenKey → 定時 PUT keepAlive
 * - Bybit: 直接連線 WS → onOpen 發送 auth + subscribe → 自動 ping/pong
 *
 * 此介面統一連線、保活、斷線的操作，讓 MultiUserDataStreamManager 無需關心交易所差異。
 */
public interface ExchangeStreamProvider {

    /**
     * 建立 WebSocket 連線
     *
     * @param apiKey    用戶 API Key
     * @param secretKey 用戶 Secret Key
     * @param wsClient  OkHttp WebSocket Client
     * @param listener  WebSocket 事件 Listener
     * @return 連線結果（WebSocket 實例 + 連線上下文）
     */
    ConnectResult connect(String apiKey, String secretKey,
                          OkHttpClient wsClient, WebSocketListener listener);

    /**
     * 定時保活（由排程器呼叫）
     *
     * @param apiKey           用戶 API Key
     * @param connectionContext 連線上下文（Binance: listenKey, Bybit: null）
     * @return HTTP status code（200=正常, 400/401=需重連, -1=錯誤）
     */
    int keepAlive(String apiKey, String connectionContext);

    /**
     * 斷線清理
     *
     * @param apiKey           用戶 API Key
     * @param connectionContext 連線上下文
     */
    void cleanup(String apiKey, String connectionContext);

    /**
     * 交易所名稱（BINANCE / BYBIT）
     */
    String getExchangeName();

    /**
     * 連線結果
     *
     * @param webSocket         WebSocket 實例
     * @param connectionContext 連線上下文（Binance: listenKey, Bybit: null）
     */
    record ConnectResult(WebSocket webSocket, String connectionContext) {}
}
