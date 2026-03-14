package com.trader.user.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.util.BinanceSignatureUtil;
import com.trader.user.dto.ApiKeyHealthResponse;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * API Key 健康檢查服務
 *
 * 呼叫 Binance GET /fapi/v2/balance 驗證 Key 是否有效、是否有合約權限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyHealthService {

    private final UserApiKeyService userApiKeyService;
    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;

    /**
     * 測試用戶 API Key 的有效性
     */
    public ApiKeyHealthResponse testApiKey(String userId) {
        Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);

        if (keysOpt.isEmpty()) {
            return ApiKeyHealthResponse.builder()
                    .valid(false)
                    .exchange("BINANCE")
                    .message("尚未設定 API Key")
                    .canTrade(false)
                    .futuresEnabled(false)
                    .build();
        }

        BinanceKeys keys = keysOpt.get();
        String queryString = "timestamp=" + System.currentTimeMillis();
        String signature = BinanceSignatureUtil.sign(queryString, keys.secretKey());
        String url = binanceConfig.getBaseUrl() + "/fapi/v2/balance?" + queryString + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", keys.apiKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (response.isSuccessful()) {
                return ApiKeyHealthResponse.builder()
                        .valid(true)
                        .exchange("BINANCE")
                        .message("API Key 有效，合約帳戶連線正常")
                        .canTrade(true)
                        .futuresEnabled(true)
                        .build();
            }

            // 常見錯誤碼處理
            if (body.contains("-2015")) {
                return ApiKeyHealthResponse.builder()
                        .valid(false)
                        .exchange("BINANCE")
                        .message("API Key 無效或已過期")
                        .canTrade(false)
                        .futuresEnabled(false)
                        .build();
            }
            if (body.contains("-2014")) {
                return ApiKeyHealthResponse.builder()
                        .valid(false)
                        .exchange("BINANCE")
                        .message("API Key 格式錯誤")
                        .canTrade(false)
                        .futuresEnabled(false)
                        .build();
            }
            if (body.contains("-1022")) {
                return ApiKeyHealthResponse.builder()
                        .valid(false)
                        .exchange("BINANCE")
                        .message("簽名無效，Secret Key 可能有誤")
                        .canTrade(false)
                        .futuresEnabled(false)
                        .build();
            }
            if (body.contains("-4004") || body.contains("futures")) {
                return ApiKeyHealthResponse.builder()
                        .valid(true)
                        .exchange("BINANCE")
                        .message("API Key 有效，但尚未開通合約帳戶")
                        .canTrade(false)
                        .futuresEnabled(false)
                        .build();
            }

            return ApiKeyHealthResponse.builder()
                    .valid(false)
                    .exchange("BINANCE")
                    .message("驗證失敗: " + body.substring(0, Math.min(body.length(), 200)))
                    .canTrade(false)
                    .futuresEnabled(false)
                    .build();

        } catch (Exception e) {
            log.error("API Key 健康檢查失敗: userId={}, error={}", userId, e.getMessage());
            return ApiKeyHealthResponse.builder()
                    .valid(false)
                    .exchange("BINANCE")
                    .message("連線失敗: " + e.getMessage())
                    .canTrade(false)
                    .futuresEnabled(false)
                    .build();
        }
    }
}
