package com.trader.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.BinanceConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MarketIndicatorService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String DEFAULT_INTERVAL = "1m";
    private static final int KLINE_LIMIT = 500;

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MarketIndicatorService(OkHttpClient httpClient, BinanceConfig binanceConfig, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the EMA for a given symbol/period using Binance klines as the data source.
     * Results are cached briefly to avoid excessive API usage.
     */
    public double getEMA(String symbol, int period) {
        if (symbol == null || symbol.isBlank() || period <= 1) {
            return Double.NaN;
        }

        String cacheKey = symbol + ":" + period;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isExpired()) {
                // Return stale value immediately and refresh in background to avoid blocking trading flow.
                refreshAsync(cacheKey, symbol, period, cached);
            }
            return cached.value;
        }

        // Cache miss: fetch synchronously once. If it fails, return NaN.
        try {
            double ema = fetchEMA(symbol, period);
            if (!Double.isNaN(ema)) {
                cache.put(cacheKey, new CacheEntry(ema, System.currentTimeMillis(), new AtomicBoolean(false)));
            }
            return ema;
        } catch (Exception e) {
            log.warn("EMA fetch failed: symbol={} period={} err={}", symbol, period, e.getMessage());
            return Double.NaN;
        }
    }

    private double fetchEMA(String symbol, int period) throws IOException {
        String url = binanceConfig.getBaseUrl()
                + "/fapi/v1/klines?symbol=" + symbol
                + "&interval=" + DEFAULT_INTERVAL
                + "&limit=" + KLINE_LIMIT;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Double.NaN;
            }

            JsonNode root = objectMapper.readTree(Objects.requireNonNull(response.body()).string());
            if (!root.isArray() || root.isEmpty()) {
                return Double.NaN;
            }

            double multiplier = 2.0 / (period + 1.0);
            double ema = Double.NaN;

            for (JsonNode kline : root) {
                // Kline close price is index 4
                double close = kline.get(4).asDouble();
                if (Double.isNaN(ema)) {
                    ema = close; // seed EMA with first close
                } else {
                    ema = (close - ema) * multiplier + ema;
                }
            }

            return ema;
        }
    }

    private void refreshAsync(String cacheKey, String symbol, int period, CacheEntry cached) {
        if (!cached.refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                double ema = fetchEMA(symbol, period);
                if (!Double.isNaN(ema)) {
                    cache.put(cacheKey, new CacheEntry(ema, System.currentTimeMillis(), new AtomicBoolean(false)));
                }
            } catch (Exception e) {
                log.debug("EMA refresh failed: symbol={} period={} err={}", symbol, period, e.getMessage());
            } finally {
                cached.refreshing.set(false);
            }
        });
    }

    private record CacheEntry(double value, long timestampMs, AtomicBoolean refreshing) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > CACHE_TTL.toMillis();
        }
    }
}
