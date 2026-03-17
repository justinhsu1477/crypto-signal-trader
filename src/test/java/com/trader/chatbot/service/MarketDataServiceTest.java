package com.trader.chatbot.service;

import com.google.gson.JsonObject;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.DailySignalReportRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MarketDataService — 市場數據整合")
class MarketDataServiceTest {

    @Mock private BinanceFuturesService binanceFuturesService;
    @Mock private DailySignalReportRepository dailySignalReportRepository;
    @Mock private TradeRepository tradeRepository;
    @Mock private UserRepository userRepository;
    @Mock private OkHttpClient okHttpClient;

    private MarketDataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MarketDataService(binanceFuturesService, dailySignalReportRepository,
                tradeRepository, userRepository, okHttpClient);
    }

    @Nested
    @DisplayName("getMarketOverview")
    class MarketOverviewTests {

        @Test
        @DisplayName("包含 BTC 行情和 Funding Rate")
        void returnsMarketData() {
            JsonObject ticker = new JsonObject();
            ticker.addProperty("lastPrice", 67500.0);
            ticker.addProperty("priceChangePercent", 2.5);
            ticker.addProperty("highPrice", 68000.0);
            ticker.addProperty("lowPrice", 66000.0);
            ticker.addProperty("quoteVolume", 5_000_000_000.0);
            when(binanceFuturesService.get24hTicker("BTCUSDT")).thenReturn(ticker);

            JsonObject funding = new JsonObject();
            funding.addProperty("fundingRate", 0.0003);
            when(binanceFuturesService.getFundingRate("BTCUSDT")).thenReturn(funding);

            String result = service.getMarketOverview();

            assertThat(result).contains("67500.00");
            assertThat(result).contains("2.50%");
            assertThat(result).contains("5000M");
            assertThat(result).contains("0.0300%");
            assertThat(result).contains("偏多");
        }

        @Test
        @DisplayName("負 Funding Rate 顯示偏空")
        void negativeFundingRate() {
            JsonObject ticker = new JsonObject();
            ticker.addProperty("lastPrice", 60000.0);
            ticker.addProperty("priceChangePercent", -3.0);
            ticker.addProperty("highPrice", 62000.0);
            ticker.addProperty("lowPrice", 59000.0);
            ticker.addProperty("quoteVolume", 3_000_000_000.0);
            when(binanceFuturesService.get24hTicker("BTCUSDT")).thenReturn(ticker);

            JsonObject funding = new JsonObject();
            funding.addProperty("fundingRate", -0.0005);
            when(binanceFuturesService.getFundingRate("BTCUSDT")).thenReturn(funding);

            String result = service.getMarketOverview();

            assertThat(result).contains("偏空");
        }

        @Test
        @DisplayName("API 失敗時不拋異常")
        void apiFailure_gracefulDegradation() {
            when(binanceFuturesService.get24hTicker("BTCUSDT"))
                    .thenThrow(new RuntimeException("API error"));

            String result = service.getMarketOverview();

            assertThat(result).contains("資料載入失敗");
        }
    }

    @Nested
    @DisplayName("getUserPositions")
    class PositionTests {

        @Test
        @DisplayName("有持倉時顯示明細")
        void withPositions() {
            Trade trade = Trade.builder()
                    .symbol("BTCUSDT").side("LONG").entryPrice(65000.0)
                    .entryQuantity(0.1).stopLoss(63000.0).leverage(10).build();
            when(tradeRepository.findByUserIdAndStatus("u1", "OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getMarkPrice("BTCUSDT")).thenReturn(66000.0);

            String result = service.getUserPositions("u1");

            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("LONG");
            assertThat(result).contains("65000.00");
            assertThat(result).contains("SL：$63000.00");
            assertThat(result).contains("10x");
            assertThat(result).contains("未實現 PnL");
        }

        @Test
        @DisplayName("無持倉時顯示提示")
        void noPositions() {
            when(tradeRepository.findByUserIdAndStatus("u1", "OPEN")).thenReturn(List.of());

            String result = service.getUserPositions("u1");

            assertThat(result).contains("目前無持倉");
        }

        @Test
        @DisplayName("取得即時價格失敗時仍顯示基本資訊")
        void priceFailure_stillShowsBasicInfo() {
            Trade trade = Trade.builder()
                    .symbol("BTCUSDT").side("SHORT").entryPrice(68000.0)
                    .entryQuantity(0.05).leverage(20).build();
            when(tradeRepository.findByUserIdAndStatus("u1", "OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getMarkPrice("BTCUSDT"))
                    .thenThrow(new RuntimeException("price error"));

            String result = service.getUserPositions("u1");

            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("SHORT");
            assertThat(result).doesNotContain("未實現 PnL");
        }
    }

    @Nested
    @DisplayName("getSignalReportSummary")
    class SignalReportTests {

        @Test
        @DisplayName("有日報時顯示摘要")
        void withReports() {
            DailySignalReport report = DailySignalReport.builder()
                    .reportDate(LocalDate.of(2026, 3, 16))
                    .totalSignals(15).longCount(10).shortCount(5)
                    .avgConfidence(72.0).totalSources(3).build();
            when(dailySignalReportRepository.findByReportDate(LocalDate.of(2026, 3, 16)))
                    .thenReturn(Optional.of(report));

            String result = service.getSignalReportSummary();

            assertThat(result).contains("2026-03-16");
            assertThat(result).contains("15 條訊號");
            assertThat(result).contains("10L/5S");
            assertThat(result).contains("72/100");
            assertThat(result).contains("3 個");
        }

        @Test
        @DisplayName("無日報時顯示提示")
        void noReports() {
            when(dailySignalReportRepository.findByReportDate(any())).thenReturn(Optional.empty());

            String result = service.getSignalReportSummary();

            assertThat(result).contains("近 3 天無日報資料");
        }
    }

    @Nested
    @DisplayName("getAllUsersSummary")
    class AllUsersSummaryTests {

        @Test
        @DisplayName("有用戶有交易時顯示概覽")
        void withUsersAndTrades() {
            User user1 = User.builder().userId("u1").name("Alice").email("alice@test.com").build();
            User user2 = User.builder().userId("u2").name("Bob").email("bob@test.com").build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2));

            // 聚合統計：userId, totalTrades, wins, pnl
            Object[] stats1 = new Object[]{"u1", 10L, 7L, 500.0};
            Object[] stats2 = new Object[]{"u2", 5L, 2L, -100.0};
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(List.of(stats1, stats2));

            Trade openTrade = Trade.builder().userId("u1").symbol("BTCUSDT").side("LONG").build();
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(openTrade));

            String result = service.getAllUsersSummary();

            assertThat(result).contains("Alice");
            assertThat(result).contains("Bob");
            assertThat(result).contains("持倉：1");   // Alice has 1 open
            assertThat(result).contains("持倉：0");   // Bob has 0 open
            assertThat(result).contains("70%");        // Alice 7/10
            assertThat(result).contains("+500.00");
            assertThat(result).contains("-100.00");
        }

        @Test
        @DisplayName("無交易資料時顯示提示")
        void noData() {
            when(userRepository.findAll()).thenReturn(Collections.emptyList());
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(Collections.emptyList());

            String result = service.getAllUsersSummary();

            assertThat(result).contains("無任何交易資料");
        }

        @Test
        @DisplayName("有持倉但無已平倉的用戶也顯示")
        void openOnlyUser() {
            User user = User.builder().userId("u3").name("Charlie").email("c@test.com").build();
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(tradeRepository.aggregateStatsPerUser()).thenReturn(Collections.emptyList());

            Trade openTrade = Trade.builder().userId("u3").symbol("ETHUSDT").side("SHORT").build();
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(openTrade));

            String result = service.getAllUsersSummary();

            assertThat(result).contains("Charlie");
            assertThat(result).contains("持倉：1");
            assertThat(result).contains("已平倉：0");
        }
    }

    @Nested
    @DisplayName("getMarketOverview 部分失敗")
    class MarketOverviewPartialFailureTests {

        @Test
        @DisplayName("Funding Rate 失敗但 BTC 行情正常回傳")
        void fundingRateFailure_tickerStillReturned() {
            JsonObject ticker = new JsonObject();
            ticker.addProperty("lastPrice", 67500.0);
            ticker.addProperty("priceChangePercent", 2.5);
            ticker.addProperty("highPrice", 68000.0);
            ticker.addProperty("lowPrice", 66000.0);
            ticker.addProperty("quoteVolume", 5_000_000_000.0);
            when(binanceFuturesService.get24hTicker("BTCUSDT")).thenReturn(ticker);
            when(binanceFuturesService.getFundingRate("BTCUSDT"))
                    .thenThrow(new RuntimeException("funding error"));

            String result = service.getMarketOverview();

            assertThat(result).contains("67500.00");
            assertThat(result).doesNotContain("資料載入失敗");
        }

        @Test
        @DisplayName("中性 Funding Rate 顯示中性")
        void neutralFundingRate() {
            JsonObject ticker = new JsonObject();
            ticker.addProperty("lastPrice", 65000.0);
            ticker.addProperty("priceChangePercent", 0.1);
            ticker.addProperty("highPrice", 65500.0);
            ticker.addProperty("lowPrice", 64500.0);
            ticker.addProperty("quoteVolume", 2_000_000_000.0);
            when(binanceFuturesService.get24hTicker("BTCUSDT")).thenReturn(ticker);

            JsonObject funding = new JsonObject();
            funding.addProperty("fundingRate", 0.00005); // 中性範圍
            when(binanceFuturesService.getFundingRate("BTCUSDT")).thenReturn(funding);

            String result = service.getMarketOverview();

            assertThat(result).contains("中性");
        }
    }

    @Nested
    @DisplayName("getAllUsersSummary 異常測試")
    class AllUsersSummaryExceptionTests {

        @Test
        @DisplayName("userRepository 異常時回傳載入失敗")
        void userRepoFailure_gracefulDegradation() {
            when(userRepository.findAll()).thenThrow(new RuntimeException("DB error"));

            String result = service.getAllUsersSummary();

            assertThat(result).contains("資料載入失敗");
        }

        @Test
        @DisplayName("aggregateStatsPerUser 異常時回傳載入失敗")
        void statsRepoFailure_gracefulDegradation() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(tradeRepository.aggregateStatsPerUser()).thenThrow(new RuntimeException("DB error"));

            String result = service.getAllUsersSummary();

            assertThat(result).contains("資料載入失敗");
        }
    }

    @Nested
    @DisplayName("fetchFearGreedIndex")
    class FearGreedTests {

        @Test
        @DisplayName("解析 API 回應成功")
        void parsesResponse() throws IOException {
            String jsonResponse = """
                    {"data":[{"value":"25","value_classification":"Extreme Fear"}]}
                    """;
            Call mockCall = mock(Call.class);
            Response response = new Response.Builder()
                    .request(new Request.Builder().url("https://api.alternative.me/fcp/v1/fear-and-greed-index/?limit=1").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(jsonResponse, MediaType.parse("application/json")))
                    .build();
            when(okHttpClient.newCall(any())).thenReturn(mockCall);
            when(mockCall.execute()).thenReturn(response);

            String result = service.fetchFearGreedIndex();

            assertThat(result).contains("25/100");
            assertThat(result).contains("Extreme Fear");
            assertThat(result).contains("😱");
        }

        @Test
        @DisplayName("API 失敗回傳 null")
        void apiFailure_returnsNull() throws IOException {
            Call mockCall = mock(Call.class);
            when(okHttpClient.newCall(any())).thenReturn(mockCall);
            when(mockCall.execute()).thenThrow(new IOException("timeout"));

            String result = service.fetchFearGreedIndex();

            assertThat(result).isNull();
        }
    }
}
