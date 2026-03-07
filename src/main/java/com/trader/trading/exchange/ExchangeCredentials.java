package com.trader.trading.exchange;

/**
 * 交易所認證上下文（取代 ExchangeKeys record）
 * 用於 ThreadLocal per-user 認證切換
 */
public record ExchangeCredentials(
    String apiKey,
    String secretKey
) {}
