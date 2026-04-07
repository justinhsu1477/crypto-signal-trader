package com.trader.trading.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.trading.entity.BalanceSnapshot;
import com.trader.trading.repository.BalanceSnapshotRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BalanceSnapshotTaskTest {

    private UserApiKeyService userApiKeyService;
    private BalanceSnapshotRepository balanceSnapshotRepository;
    private OkHttpClient httpClient;
    private BinanceConfig binanceConfig;
    private BalanceSnapshotTask task;

    @BeforeEach
    void setUp() {
        userApiKeyService = mock(UserApiKeyService.class);
        balanceSnapshotRepository = mock(BalanceSnapshotRepository.class);
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
        when(binanceConfig.getBaseUrl()).thenReturn("https://fapi.binance.com");
        task = new BalanceSnapshotTask(userApiKeyService, balanceSnapshotRepository, httpClient, binanceConfig);
    }

    @Test
    @DisplayName("正常快照 → 儲存餘額")
    void snapshotSuccess() throws IOException {
        BinanceKeys keys = new BinanceKeys("apiKey", "secretKey");
        when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                .thenReturn(Map.of("user-1", keys));
        when(balanceSnapshotRepository.existsByUserIdAndSnapshotDate(eq("user-1"), any()))
                .thenReturn(false);

        mockHttpResponse(200, "{\"totalWalletBalance\":\"12345.67\"}");

        task.snapshotDailyBalances();

        ArgumentCaptor<BalanceSnapshot> captor = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("12345.67"));
    }

    @Test
    @DisplayName("已有今日快照 → 跳過")
    void skipExisting() {
        when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                .thenReturn(Map.of("user-1", new BinanceKeys("api", "secret")));
        when(balanceSnapshotRepository.existsByUserIdAndSnapshotDate(eq("user-1"), any()))
                .thenReturn(true);

        task.snapshotDailyBalances();

        verify(balanceSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("Binance API 失敗 → 不存快照，不拋異常")
    void apiFailure() throws IOException {
        when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                .thenReturn(Map.of("user-1", new BinanceKeys("api", "secret")));
        when(balanceSnapshotRepository.existsByUserIdAndSnapshotDate(eq("user-1"), any()))
                .thenReturn(false);

        mockHttpResponse(401, "{\"code\":-2015}");

        task.snapshotDailyBalances();

        verify(balanceSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("多用戶 → 各自獨立快照")
    void multipleUsers() throws IOException {
        when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                .thenReturn(Map.of(
                        "user-1", new BinanceKeys("api1", "secret1"),
                        "user-2", new BinanceKeys("api2", "secret2")
                ));
        when(balanceSnapshotRepository.existsByUserIdAndSnapshotDate(anyString(), any()))
                .thenReturn(false);

        // 每次 newCall 都回傳新的 Call mock 和新的 Response（body 只能讀一次）
        Call call = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenAnswer(inv -> new Response.Builder()
                .request(new Request.Builder().url("https://fapi.binance.com/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("{\"totalWalletBalance\":\"1000.00\"}", MediaType.parse("application/json")))
                .build());

        task.snapshotDailyBalances();

        verify(balanceSnapshotRepository, times(2)).save(any(BalanceSnapshot.class));
    }

    @Test
    @DisplayName("回傳無 totalWalletBalance → 不存快照")
    void noBalanceField() throws IOException {
        when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                .thenReturn(Map.of("user-1", new BinanceKeys("api", "secret")));
        when(balanceSnapshotRepository.existsByUserIdAndSnapshotDate(eq("user-1"), any()))
                .thenReturn(false);

        mockHttpResponse(200, "{\"assets\":[]}");

        task.snapshotDailyBalances();

        verify(balanceSnapshotRepository, never()).save(any());
    }

    private void mockHttpResponse(int code, String body) throws IOException {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://fapi.binance.com/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
        Call call = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
    }
}
