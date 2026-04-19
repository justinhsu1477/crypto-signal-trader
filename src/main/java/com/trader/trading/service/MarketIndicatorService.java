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

    /**
     * 取得最新一期 Funding Rate（永續合約獨有指標）。
     * 正值 = 多頭支付空頭（市場偏多），負值 = 空頭支付多頭（市場偏空）。
     * Binance 每 8 小時結算一次。
     */
    public double getFundingRate(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Double.NaN;
        }

        String cacheKey = symbol + ":FUNDING";
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isExpired()) {
                refreshGenericAsync(cacheKey, () -> fetchFundingRate(symbol), cached);
            }
            return cached.value;
        }

        try {
            double rate = fetchFundingRate(symbol);
            if (!Double.isNaN(rate)) {
                cache.put(cacheKey, new CacheEntry(rate, System.currentTimeMillis(), new AtomicBoolean(false)));
            }
            return rate;
        } catch (Exception e) {
            log.warn("Funding rate fetch failed: symbol={} err={}", symbol, e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * 取得 RSI(period)。RSI < 30 超賣，RSI > 70 超買。
     */
    public double getRSI(String symbol, int period) {
        if (symbol == null || symbol.isBlank() || period <= 1) {
            return Double.NaN;
        }

        String cacheKey = symbol + ":RSI:" + period;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isExpired()) {
                refreshGenericAsync(cacheKey, () -> fetchRSI(symbol, period), cached);
            }
            return cached.value;
        }

        try {
            double rsi = fetchRSI(symbol, period);
            if (!Double.isNaN(rsi)) {
                cache.put(cacheKey, new CacheEntry(rsi, System.currentTimeMillis(), new AtomicBoolean(false)));
            }
            return rsi;
        } catch (Exception e) {
            log.warn("RSI fetch failed: symbol={} period={} err={}", symbol, period, e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * 取得近 4 小時的 Open Interest 變化百分比。
     * 正值 = OI 增加（新倉位進入），負值 = OI 減少（平倉潮）。
     */
    public double getOpenInterestChange4h(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Double.NaN;
        }

        String cacheKey = symbol + ":OI_CHANGE_4H";
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isExpired()) {
                refreshGenericAsync(cacheKey, () -> fetchOpenInterestChange(symbol), cached);
            }
            return cached.value;
        }

        try {
            double change = fetchOpenInterestChange(symbol);
            if (!Double.isNaN(change)) {
                cache.put(cacheKey, new CacheEntry(change, System.currentTimeMillis(), new AtomicBoolean(false)));
            }
            return change;
        } catch (Exception e) {
            log.warn("OI change fetch failed: symbol={} err={}", symbol, e.getMessage());
            return Double.NaN;
        }
    }

    private double fetchFundingRate(String symbol) throws IOException {
        String url = binanceConfig.getBaseUrl()
                + "/fapi/v1/fundingRate?symbol=" + symbol + "&limit=1";

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Double.NaN;
            }
            JsonNode root = objectMapper.readTree(Objects.requireNonNull(response.body()).string());
            if (!root.isArray() || root.isEmpty()) {
                return Double.NaN;
            }
            return root.get(0).get("fundingRate").asDouble();
        }
    }

    private double fetchRSI(String symbol, int period) throws IOException {
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

            double avgGain = 0.0;
            double avgLoss = 0.0;
            double prevClose = root.get(0).get(4).asDouble();

            // Initial average over first 'period' changes
            for (int i = 1; i <= period && i < root.size(); i++) {
                double close = root.get(i).get(4).asDouble();
                double change = close - prevClose;
                if (change > 0) avgGain += change;
                else avgLoss += Math.abs(change);
                prevClose = close;
            }
            avgGain /= period;
            avgLoss /= period;

            // Smoothed RSI (Wilder's method)
            for (int i = period + 1; i < root.size(); i++) {
                double close = root.get(i).get(4).asDouble();
                double change = close - prevClose;
                if (change > 0) {
                    avgGain = (avgGain * (period - 1) + change) / period;
                    avgLoss = (avgLoss * (period - 1)) / period;
                } else {
                    avgGain = (avgGain * (period - 1)) / period;
                    avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
                }
                prevClose = close;
            }

            if (avgLoss == 0) return 100.0;
            double rs = avgGain / avgLoss;
            return 100.0 - (100.0 / (1.0 + rs));
        }
    }

    private double fetchOpenInterestChange(String symbol) throws IOException {
        // 使用 /futures/data/openInterestHist 取得歷史 OI（5 分鐘間隔，48 筆 = 4 小時）
        String url = binanceConfig.getBaseUrl()
                + "/futures/data/openInterestHist?symbol=" + symbol
                + "&period=5m&limit=48";

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Double.NaN;
            }

            JsonNode root = objectMapper.readTree(Objects.requireNonNull(response.body()).string());
            if (!root.isArray() || root.size() < 2) {
                return Double.NaN;
            }

            double oldest = root.get(0).get("sumOpenInterestValue").asDouble();
            double newest = root.get(root.size() - 1).get("sumOpenInterestValue").asDouble();

            if (oldest <= 0) return Double.NaN;
            return (newest - oldest) / oldest; // 變化百分比
        }
    }

    private void refreshGenericAsync(String cacheKey, FetchTask task, CacheEntry cached) {
        if (!cached.refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                double value = task.fetch();
                if (!Double.isNaN(value)) {
                    cache.put(cacheKey, new CacheEntry(value, System.currentTimeMillis(), new AtomicBoolean(false)));
                }
            } catch (Exception e) {
                log.debug("Async refresh failed: key={} err={}", cacheKey, e.getMessage());
            } finally {
                cached.refreshing.set(false);
            }
        });
    }

    @FunctionalInterface
    private interface FetchTask {
        double fetch() throws IOException;
    }

    private record CacheEntry(double value, long timestampMs, AtomicBoolean refreshing) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > CACHE_TTL.toMillis();
        }
    }
}
