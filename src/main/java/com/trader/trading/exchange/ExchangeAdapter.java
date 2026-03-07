package com.trader.trading.exchange;

import com.trader.shared.model.OrderResult;

import java.util.Map;

/**
 * 交易所適配器介面
 *
 * 設計原則：
 * 1. 方法簽名使用正規化型別，不暴露交易所特有概念
 * 2. Side 使用 "BUY"/"SELL"（正規化），Adapter 內部轉換
 * 3. 所有方法拋出 RuntimeException，由業務層統一處理
 * 4. 不涉及業務邏輯（風控/DCA/熔斷），純粹是交易所 API 操作
 */
public interface ExchangeAdapter {

    // ==================== 帳戶查詢 ====================

    /** 取得 USDT 可用餘額 */
    double getAvailableBalance();

    /** 取得帳戶餘額原始 JSON（供 Controller 直接回傳） */
    String getAccountBalanceRaw();

    /** 取得所有持倉原始 JSON */
    String getPositionsRaw();

    /** 取得交易對資訊原始 JSON */
    String getExchangeInfoRaw();

    // ==================== 持倉查詢 ====================

    /**
     * 取得某交易對的當前持倉數量
     * @return 正數=多倉, 負數=空倉, 0=無持倉
     *         Binance: 直接用 positionAmt（有號）
     *         Bybit: size × (side=="Buy" ? 1 : -1)
     */
    double getCurrentPositionAmount(String symbol);

    /** 批量取得所有持倉（symbol → signedAmount） */
    Map<String, Double> getAllPositionAmounts();

    /** 取得活躍持倉數量 */
    int getActivePositionCount();

    // ==================== 市場數據 ====================

    /** 取得市場價格 */
    double getMarkPrice(String symbol);

    // ==================== 訂單操作 ====================

    /** 下限價單 */
    OrderResult placeLimitOrder(String symbol, String side, double price, double quantity);

    /** 下市價單 */
    OrderResult placeMarketOrder(String symbol, String side, double quantity);

    /**
     * 設定止損
     * Binance: 獨立 STOP_MARKET Algo 訂單
     * Bybit: /v5/position/trading-stop 或下單時 inline
     */
    OrderResult setStopLoss(String symbol, String closeSide, double triggerPrice, double quantity);

    /**
     * 設定止盈
     * Binance: 獨立 TAKE_PROFIT_MARKET Algo 訂單
     * Bybit: /v5/position/trading-stop 或下單時 inline
     */
    OrderResult setTakeProfit(String symbol, String closeSide, double triggerPrice, double quantity);

    /** 取消單筆訂單 */
    void cancelOrder(String symbol, String orderId);

    /** 取消所有訂單（含 SL/TP） */
    void cancelAllOrders(String symbol);

    /** 只取消 SL/TP 訂單，保留入場掛單（DCA 用） */
    void cancelSLTPOrders(String symbol);

    // ==================== 查詢訂單 ====================

    /** 是否有未成交的 LIMIT 入場掛單 */
    boolean hasOpenEntryOrders(String symbol);

    /** 查詢當前 SL/TP 價格: [0]=SL, [1]=TP, 0 表示不存在 */
    double[] getCurrentSLTPPrices(String symbol);

    /** 取得未成交訂單原始 JSON */
    String getOpenOrdersRaw(String symbol);

    /** 查詢強制平倉記錄原始 JSON */
    String getForceOrdersRaw();

    // ==================== 帳戶配置 ====================

    /** 設定槓桿 */
    void setLeverage(String symbol, int leverage);

    /** 設定保證金模式（ISOLATED / CROSSED） */
    void setMarginType(String symbol, String marginType);

    // ==================== 格式化 ====================

    /** 格式化價格（各交易所精度不同） */
    String formatPrice(double price);

    /** 格式化數量（各交易所精度不同） */
    String formatQuantity(String symbol, double quantity);

    // ==================== 認證上下文 ====================

    /**
     * 設定當前線程的 per-user 認證
     * 取代現有 ThreadLocal&lt;BinanceKeys&gt; 模式
     */
    void setCredentials(ExchangeCredentials credentials);

    /** 清除當前線程認證 */
    void clearCredentials();

    /** 取得交易所名稱（用於日誌 / DB） */
    String getExchangeName();
}
