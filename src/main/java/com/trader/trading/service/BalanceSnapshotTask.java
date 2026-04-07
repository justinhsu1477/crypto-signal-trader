package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.BinanceConfig;
import com.trader.shared.util.BinanceSignatureUtil;
import com.trader.trading.entity.BalanceSnapshot;
import com.trader.trading.repository.BalanceSnapshotRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 每日資產快照排程
 *
 * 每天 23:50（台北時間）對所有有 API Key 的用戶查詢 Binance 帳戶餘額並快照。
 * 資料用於前端「資產淨值曲線」圖表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceSnapshotTask {

    private final UserApiKeyService userApiKeyService;
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final Gson gson = new Gson();

    /**
     * 每天 23:50 台北時間執行
     */
    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Taipei")
    public void snapshotDailyBalances() {
        log.info("開始每日資產快照...");
        LocalDate today = LocalDate.now(AppConstants.ZONE_ID);

        Map<String, BinanceKeys> allKeys = userApiKeyService.getAllBinanceKeys("BINANCE");
        int success = 0;
        int failed = 0;

        for (Map.Entry<String, BinanceKeys> entry : allKeys.entrySet()) {
            String userId = entry.getKey();

            // 跳過已有今日快照的用戶
            if (balanceSnapshotRepository.existsByUserIdAndSnapshotDate(userId, today)) {
                continue;
            }

            try {
                BigDecimal balance = fetchTotalBalance(entry.getValue());
                if (balance != null) {
                    balanceSnapshotRepository.save(BalanceSnapshot.builder()
                            .userId(userId)
                            .snapshotDate(today)
                            .balance(balance)
                            .build());
                    success++;
                }
            } catch (Exception e) {
                log.warn("用戶 {} 資產快照失敗: {}", userId, e.getMessage());
                failed++;
            }
        }

        log.info("每日資產快照完成: 成功={}, 失敗={}", success, failed);
    }

    /**
     * 查詢 Binance 合約帳戶總權益（totalWalletBalance）
     */
    private BigDecimal fetchTotalBalance(BinanceKeys keys) {
        String queryString = "timestamp=" + System.currentTimeMillis();
        String signature = BinanceSignatureUtil.sign(queryString, keys.secretKey());
        String url = binanceConfig.getBaseUrl() + "/fapi/v2/account?" + queryString + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", keys.apiKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);

            // totalWalletBalance = 錢包淨值（含未實現盈虧）
            if (json.has("totalWalletBalance")) {
                return new BigDecimal(json.get("totalWalletBalance").getAsString());
            }
            return null;
        } catch (Exception e) {
            log.debug("查詢餘額失敗: {}", e.getMessage());
            return null;
        }
    }
}
