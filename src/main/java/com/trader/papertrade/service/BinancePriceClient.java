package com.trader.papertrade.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Binance 公開 API 價格查詢（不需 API Key）
 * 獨立於 BinanceFuturesService，供 Paper Trading 模組使用
 */
@Slf4j
@Component
public class BinancePriceClient {

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public BinancePriceClient(@Value("${binance.futures.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    /**
     * 批次取得所有幣種市價（單次 API call）
     * @return Map<symbol, price>，例如 {"BTCUSDT": 67500.0, "ETHUSDT": 3500.0}
     */
    public Map<String, Double> getAllMarkPrices() {
        String url = baseUrl + "/fapi/v1/ticker/price";
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Binance API 回應失敗: " + response.code());
            }
            String body = response.body().string();
            Map<String, Double> prices = new HashMap<>();
            JsonArray arr = gson.fromJson(body, JsonArray.class);
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                prices.put(obj.get("symbol").getAsString(), obj.get("price").getAsDouble());
            }
            return prices;
        } catch (IOException e) {
            throw new RuntimeException("批次取得市價失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 取得單一幣種市價
     */
    public double getMarkPrice(String symbol) {
        String url = baseUrl + "/fapi/v1/ticker/price?symbol=" + symbol;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Binance API 回應失敗: " + response.code());
            }
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            return json.get("price").getAsDouble();
        } catch (IOException e) {
            throw new RuntimeException("取得市價失敗: " + e.getMessage(), e);
        }
    }
}
