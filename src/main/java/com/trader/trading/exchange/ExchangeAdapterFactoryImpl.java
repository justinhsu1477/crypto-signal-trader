package com.trader.trading.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * ExchangeAdapterFactory 實作
 *
 * 自動注入所有已啟用的 ExchangeAdapter Bean，
 * 根據 exchangeName 路由到對應實例。
 */
@Slf4j
@Service
public class ExchangeAdapterFactoryImpl implements ExchangeAdapterFactory {

    private final Map<String, ExchangeAdapter> adapters;

    public ExchangeAdapterFactoryImpl(
            @Qualifier("binanceAdapter") ExchangeAdapter binanceAdapter,
            @Qualifier("bybitAdapter") @org.springframework.beans.factory.annotation.Autowired(required = false) ExchangeAdapter bybitAdapter) {
        this.adapters = new HashMap<>();
        adapters.put(binanceAdapter.getExchangeName(), binanceAdapter);
        if (bybitAdapter != null && !bybitAdapter.getExchangeName().equals(binanceAdapter.getExchangeName())) {
            adapters.put(bybitAdapter.getExchangeName(), bybitAdapter);
        }
        log.info("ExchangeAdapterFactory 初始化完成，已註冊交易所: {}", adapters.keySet());
    }

    @Override
    public ExchangeAdapter getAdapter(String exchangeName) {
        ExchangeAdapter adapter = adapters.get(exchangeName.toUpperCase());
        if (adapter == null) {
            throw new IllegalArgumentException("不支援的交易所: " + exchangeName
                    + "，目前支援: " + adapters.keySet());
        }
        return adapter;
    }

    @Override
    public ExchangeAdapter getDefaultAdapter() {
        return adapters.get("BINANCE");
    }
}
