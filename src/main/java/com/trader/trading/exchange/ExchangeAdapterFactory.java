package com.trader.trading.exchange;

/**
 * 交易所適配器工廠
 * 根據交易所名稱取得對應 Adapter 實例
 */
public interface ExchangeAdapterFactory {

    /**
     * 取得指定交易所的 Adapter
     * @param exchangeName 交易所名稱（"BINANCE", "BYBIT"）
     * @throws IllegalArgumentException 不支援的交易所
     */
    ExchangeAdapter getAdapter(String exchangeName);

    /** 取得預設交易所的 Adapter（BINANCE） */
    ExchangeAdapter getDefaultAdapter();
}
