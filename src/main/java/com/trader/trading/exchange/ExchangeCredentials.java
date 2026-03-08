package com.trader.trading.exchange;

/**
 * 交易所認證上下文（取代 ExchangeKeys record）
 * 用於 ThreadLocal per-user 認證切換
 *
 * passphrase 為 nullable，僅 Bitget 使用。
 * Binance / Bybit 透過 2-arg 建構子維持向後相容。
 */
public record ExchangeCredentials(
    String apiKey,
    String secretKey,
    String passphrase
) {
    /** Binance / Bybit 用（向後相容，passphrase = null） */
    public ExchangeCredentials(String apiKey, String secretKey) {
        this(apiKey, secretKey, null);
    }
}
