package com.trader.trading.service;

/**
 * Binance User Data Stream WebSocket URL 建構器
 *
 * 2026-04-23 Binance 基礎架構遷移：
 * - 舊格式: {base}/{listenKey}      例如 wss://fstream.binance.com/ws/<lk>
 * - 新格式: {base}?listenKey=X&events=Y  例如 wss://fstream.binance.com/private/ws?listenKey=<lk>&events=...
 *
 * 新版必須以 query param 傳遞 listenKey，並明確列出要訂閱的 event 類型。
 *
 * ref: https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Important-WebSocket-Change-Notice
 */
public final class BinanceUserDataStreamUrlBuilder {

    /**
     * 訂閱的 event 清單，以 `/` 分隔（Binance 規格）。
     *
     * 對應處理：
     * - ORDER_TRADE_UPDATE → SL/TP 觸發、訂單成交
     * - ACCOUNT_UPDATE    → 餘額 / 持倉變動
     * - ALGO_UPDATE       → TP/SL 演算法單狀態
     * - MARGIN_CALL       → 保證金警告（安全防護）
     *
     * 註：listenKeyExpired 為生命週期事件，無需明確訂閱（Binance 會自動發送）。
     */
    static final String SUBSCRIBED_EVENTS =
            "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE/MARGIN_CALL";

    private BinanceUserDataStreamUrlBuilder() {
        // util class，禁止實例化
    }

    /**
     * 組合 User Data Stream WebSocket URL。
     *
     * @param wsBaseUrl e.g. {@code wss://fstream.binance.com/private/ws}
     * @param listenKey Binance POST /fapi/v1/listenKey 回傳的 key
     * @return 完整 URL，附帶 listenKey + events query param
     * @throws IllegalArgumentException 若 wsBaseUrl / listenKey 為 null 或空白
     */
    public static String build(String wsBaseUrl, String listenKey) {
        if (wsBaseUrl == null || wsBaseUrl.isBlank()) {
            throw new IllegalArgumentException("wsBaseUrl 不可為空");
        }
        if (listenKey == null || listenKey.isBlank()) {
            throw new IllegalArgumentException("listenKey 不可為空");
        }
        return wsBaseUrl + "?listenKey=" + listenKey + "&events=" + SUBSCRIBED_EVENTS;
    }
}
