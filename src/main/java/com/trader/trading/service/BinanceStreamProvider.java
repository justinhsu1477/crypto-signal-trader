package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

/**
 * Binance Futures User Data Stream Provider
 *
 * 連線機制：
 * 1. POST /fapi/v1/listenKey → 取得 listenKey
 * 2. WS URL = wsBaseUrl + listenKey
 * 3. 每 30 分鐘 PUT keepAlive listenKey
 * 4. 斷線時 DELETE listenKey
 *
 * listenKey 有效期 60 分鐘，需定期 keepAlive。
 */
@Slf4j
@Component
public class BinanceStreamProvider implements ExchangeStreamProvider {

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final Gson gson = new Gson();

    public BinanceStreamProvider(OkHttpClient httpClient, BinanceConfig binanceConfig) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
    }

    @Override
    public ConnectResult connect(String apiKey, String secretKey,
                                  OkHttpClient wsClient, WebSocketListener listener) {
        String listenKey = createListenKey(apiKey);
        String wsUrl = binanceConfig.getWsBaseUrl() + listenKey;
        Request request = new Request.Builder().url(wsUrl).build();
        WebSocket ws = wsClient.newWebSocket(request, listener);
        return new ConnectResult(ws, listenKey);
    }

    @Override
    public int keepAlive(String apiKey, String connectionContext) {
        if (connectionContext == null) return -1;
        return keepAliveListenKey(apiKey, connectionContext);
    }

    @Override
    public void cleanup(String apiKey, String connectionContext) {
        deleteListenKey(apiKey, connectionContext);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    // ==================== listenKey REST 操作 ====================

    String createListenKey(String apiKey) {
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("建立 listenKey 失敗: " + response.code() + " " + body);
            }
            JsonObject json = gson.fromJson(body, JsonObject.class);
            return json.get("listenKey").getAsString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("建立 listenKey 失敗: " + e.getMessage(), e);
        }
    }

    int keepAliveListenKey(String apiKey, String listenKey) {
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        } catch (Exception e) {
            log.error("Binance keepAlive request 失敗: {}", e.getMessage());
            return -1;
        }
    }

    private void deleteListenKey(String apiKey, String listenKey) {
        if (listenKey == null) return;
        String url = binanceConfig.getBaseUrl() + "/fapi/v1/listenKey";
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            log.debug("Binance listenKey 已刪除: {}", response.isSuccessful());
        } catch (Exception e) {
            log.warn("刪除 Binance listenKey 失敗: {}", e.getMessage());
        }
    }
}
