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

    /**
     * Returns the ATR (Average True Range) as a percentage of the current price.
     * For example: ATR = 1200, price = 60000 → returns 0.02 (2%).
     */
    public double getATRPercent(String symbol, int period) {
        if (symbol == null || symbol.isBlank() || period <= 1) {
            return Double.NaN;
        }

        String cacheKey = symbol + ":ATR:" + period;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isExpired()) {
                refreshATRAsync(cacheKey, symbol, period, cached);
            }
            return cached.value;
        }

        try {
            double atrPercent = fetchATRPercent(symbol, period);
            if (!Double.isNaN(atrPercent)) {
                cache.put(cacheKey, new CacheEntry(atrPercent, System.currentTimeMillis(), new AtomicBoolean(false)));
            }
            return atrPercent;
        } catch (Exception e) {
            log.warn("ATR fetch failed: symbol={} period={} err={}", symbol, period, e.getMessage());
            return Double.NaN;
        }
    }

    private double fetchATRPercent(String symbol, int period) throws IOException {
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
            if (!root.isArray() || root.size() < period + 1) {
                return Double.NaN;
            }

            // Wilder's smoothed ATR
            double atr = Double.NaN;
            double prevClose = root.get(0).get(4).asDouble();
            double lastClose = prevClose;

            for (int i = 1; i < root.size(); i++) {
                JsonNode kline = root.get(i);
                double high = kline.get(2).asDouble();
                double low = kline.get(3).asDouble();
                double close = kline.get(4).asDouble();

                double tr = Math.max(high - low,
                        Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));

                if (Double.isNaN(atr)) {
                    atr = tr; // seed
                } else {
                    atr = ((atr * (period - 1)) + tr) / period;
                }

                prevClose = close;
                lastClose = close;
            }

            if (Double.isNaN(atr) || lastClose <= 0) {
                return Double.NaN;
            }
            return atr / lastClose; // ATR as percentage of price
        }
    }

    private void refreshATRAsync(String cacheKey, String symbol, int period, CacheEntry cached) {
        if (!cached.refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                double atrPercent = fetchATRPercent(symbol, period);
                if (!Double.isNaN(atrPercent)) {
                    cache.put(cacheKey, new CacheEntry(atrPercent, System.currentTimeMillis(), new AtomicBoolean(false)));
                }
            } catch (Exception e) {
                log.debug("ATR refresh failed: symbol={} period={} err={}", symbol, period, e.getMessage());
            } finally {
                cached.refreshing.set(false);
            }
        });
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
