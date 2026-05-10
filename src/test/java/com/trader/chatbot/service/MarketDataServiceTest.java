package com.trader.chatbot.service;

import com.google.gson.JsonObject;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.DailySignalReportRepository;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.dto.signalsource.UpdateSignalSourceRequest;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.SignalSourceService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import org.springframework.data.domain.PageImpl;
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
import static org.mockito.Mockito.*;

@DisplayName("MarketDataService — 市場數據整合")
class MarketDataServiceTest {

    @Mock private BinanceFuturesService binanceFuturesService;
    @Mock private DailySignalReportRepository dailySignalReportRepository;
    @Mock private TradeRepository tradeRepository;
    @Mock private UserRepository userRepository;
    @Mock private SignalSourceConfigRepository signalSourceConfigRepository;
    @Mock private BroadcastLogRepository broadcastLogRepository;
    @Mock private SignalSourceService signalSourceService;
    @Mock private OkHttpClient okHttpClient;
    @Mock private UserApiKeyService userApiKeyService;

    private MarketDataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MarketDataService(binanceFuturesService, dailySignalReportRepository,
                tradeRepository, userRepository, signalSourceConfigRepository,
                broadcastLogRepository, signalSourceService, okHttpClient, userApiKeyService);
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
            LocalDate today = LocalDate.now();
            DailySignalReport report = DailySignalReport.builder()
                    .reportDate(today)
                    .totalSignals(15).longCount(10).shortCount(5)
                    .avgConfidence(72.0).totalSources(3).build();
            when(dailySignalReportRepository.findByReportDate(today))
                    .thenReturn(Optional.of(report));

            String result = service.getSignalReportSummary();

            assertThat(result).contains(today.toString());
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

    // ==================== 新增：訊號來源查詢測試 ====================

    @Nested
    @DisplayName("getSourceList")
    class SourceListTests {

        @Test
        @DisplayName("有來源時列出清單")
        void withSources() {
            SignalSourceConfig s1 = SignalSourceConfig.builder()
                    .name("比特幣飛揚VIP").displayName("訊號源A")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO).enabled(true).build();
            SignalSourceConfig s2 = SignalSourceConfig.builder()
                    .name("陳哥頻道").displayName("訊號源B")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW).enabled(false)
                    .riskMultiplier(0.5).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(s1, s2));

            String result = service.getSourceList();

            assertThat(result).contains("比特幣飛揚VIP");
            assertThat(result).contains("陳哥頻道");
            assertThat(result).contains("AUTO");
            assertThat(result).contains("SHADOW");
            assertThat(result).contains("啟用");
            assertThat(result).contains("停用");
            assertThat(result).contains("共 2 個來源");
        }

        @Test
        @DisplayName("無來源時顯示提示")
        void noSources() {
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(Collections.emptyList());

            String result = service.getSourceList();

            assertThat(result).contains("目前無訊號來源");
        }
    }

    @Nested
    @DisplayName("getSourcePerformance")
    class SourcePerformanceTests {

        @Test
        @DisplayName("查到來源並回傳績效")
        void withPerformanceData() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("比特幣飛揚VIP").channelId("ch1").guildId("g1")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));

            // tradeCount, winCount, totalPnl, avgPnl, maxWin, maxLoss, grossWins, grossLosses
            Object[] stats = new Object[]{10L, 7L, 500.0, 50.0, 200.0, -80.0, 700.0, -200.0};
            when(tradeRepository.getSourcePerformanceStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(stats);
            when(tradeRepository.getSourcePaperTradeStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(new Object[]{null});

            String result = service.getSourcePerformance("比特幣飛揚", "all");

            assertThat(result).contains("比特幣飛揚VIP");
            assertThat(result).contains("70%");
            assertThat(result).contains("+500.00");
            assertThat(result).contains("Profit Factor");
        }

        @Test
        @DisplayName("找不到來源時列出可用來源")
        void sourceNotFound() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("陳哥頻道").build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));

            String result = service.getSourcePerformance("不存在的頻道", "all");

            assertThat(result).contains("找不到");
            assertThat(result).contains("陳哥頻道");
        }

        @Test
        @DisplayName("Hibernate 6 nested Object[][] 結果 — 正確解開不丟 ClassCastException")
        void hibernate6NestedAggregateResult() {
            // Reproduce prod issue: Hibernate 6 回傳 native aggregate 時，
            // 可能把單 row 包成 Object[][]（外層 Object[] 包了一個 Object[] row）。
            // 舊 code 直接 ((Number) stats[0]) 會炸 ClassCastException。
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("陳哥").channelId("ch1").guildId("g1")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));

            Object[] row = new Object[]{5L, 3L, 100.0, 20.0, 80.0, -30.0, 150.0, -50.0};
            Object[] nested = new Object[]{row};  // 模擬 Hibernate 6 nested wrap
            when(tradeRepository.getSourcePerformanceStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(nested);
            when(tradeRepository.getSourcePaperTradeStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(new Object[]{null});

            String result = service.getSourcePerformance("陳哥", "all");

            assertThat(result).contains("陳哥");
            assertThat(result).contains("60%");         // 3/5 = 60%
            assertThat(result).contains("+100.00");     // totalPnl
            assertThat(result).doesNotContain("載入失敗");
        }

        @Test
        @DisplayName("tradeCount=0 的 aggregate row → 顯示「無資料」")
        void zeroTradeCountShowsNoData() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("新來源").channelId("ch1").guildId("g1")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));

            // tradeCount = 0 → extractAggregateRow 應回傳 null
            Object[] emptyStats = new Object[]{0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
            when(tradeRepository.getSourcePerformanceStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(emptyStats);
            when(tradeRepository.getSourcePaperTradeStats(eq("ch1"), eq("g1"), any()))
                    .thenReturn(new Object[]{null});

            String result = service.getSourcePerformance("新來源", "all");

            assertThat(result).contains("**真實交易**：無資料");
        }
    }

    @Nested
    @DisplayName("getSourceRecentTrades")
    class SourceRecentTradesTests {

        @Test
        @DisplayName("有交易時列出明細")
        void withTrades() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("比特幣飛揚VIP").channelId("ch1").guildId("g1")
                    .tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));

            Trade t = Trade.builder()
                    .symbol("BTCUSDT").side("LONG").status("CLOSED")
                    .entryPrice(65000.0).exitPrice(66000.0).netProfit(100.0)
                    .aiConfidence(85).build();
            when(tradeRepository.findRecentTradesBySource(eq("ch1"), eq("g1"), any()))
                    .thenReturn(List.of(t));

            String result = service.getSourceRecentTrades("飛揚", 5);

            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("LONG");
            assertThat(result).contains("65000.00");
            assertThat(result).contains("66000.00");
            assertThat(result).contains("+100.00");
            assertThat(result).contains("AI：85");
        }

        @Test
        @DisplayName("無交易時顯示提示")
        void noTrades() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .name("陳哥頻道").channelId("ch2").guildId("g2")
                    .tradeMode(SignalSourceConfig.TradeMode.SHADOW).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(source));
            when(tradeRepository.findRecentTradesBySource(eq("ch2"), eq("g2"), any()))
                    .thenReturn(Collections.emptyList());

            String result = service.getSourceRecentTrades("陳哥", 5);

            assertThat(result).contains("無交易紀錄");
        }
    }

    @Nested
    @DisplayName("getRecentBroadcasts")
    class RecentBroadcastsTests {

        @Test
        @DisplayName("有廣播紀錄時列出明細")
        void withBroadcasts() {
            BroadcastLog bl = BroadcastLog.builder()
                    .signalAction("ENTRY").symbol("BTCUSDT").side("LONG")
                    .sourceAuthor("飛揚老師")
                    .successCount(3).failCount(0)
                    .skippedNoSub(1).skippedNoKey(0).skippedNotAssigned(0)
                    .aiConfidence(90).build();
            when(broadcastLogRepository.findAllByOrderByCreatedAtDesc(any()))
                    .thenReturn(new PageImpl<>(List.of(bl)));

            String result = service.getRecentBroadcasts("", 5);

            assertThat(result).contains("ENTRY");
            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("飛揚老師");
            assertThat(result).contains("成功：3");
        }

        @Test
        @DisplayName("按來源篩選")
        void filterBySource() {
            BroadcastLog bl = BroadcastLog.builder()
                    .signalAction("CLOSE").symbol("ETHUSDT").side("SHORT")
                    .sourceAuthor("陳哥")
                    .successCount(2).failCount(1)
                    .skippedNoSub(0).skippedNoKey(0).skippedNotAssigned(0)
                    .build();
            when(broadcastLogRepository.findBySourceAuthorContainingIgnoreCaseOrderByCreatedAtDesc(
                    eq("陳哥"), any())).thenReturn(new PageImpl<>(List.of(bl)));

            String result = service.getRecentBroadcasts("陳哥", 5);

            assertThat(result).contains("陳哥");
            assertThat(result).contains("CLOSE");
            assertThat(result).contains("ETHUSDT");
        }

        @Test
        @DisplayName("無紀錄時顯示提示")
        void noBroadcasts() {
            when(broadcastLogRepository.findAllByOrderByCreatedAtDesc(any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            String result = service.getRecentBroadcasts("", 5);

            assertThat(result).contains("無廣播紀錄");
        }
    }

    @Nested
    @DisplayName("updateSourceTradeMode")
    class UpdateSourceTradeModeTests {

        @Test
        @DisplayName("成功修改來源模式")
        void updateMode_成功() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).name("陳哥VIP").tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(source));
            when(signalSourceService.updateSource(eq(1L), any(UpdateSignalSourceRequest.class))).thenReturn(source);

            String result = service.updateSourceTradeMode("陳哥", "SHADOW");

            assertThat(result).contains("已成功");
            assertThat(result).contains("陳哥VIP");
            assertThat(result).contains("AUTO");
            assertThat(result).contains("SHADOW");
        }

        @Test
        @DisplayName("來源不存在")
        void updateMode_來源不存在() {
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

            String result = service.updateSourceTradeMode("不存在", "SHADOW");

            assertThat(result).contains("找不到");
        }

        @Test
        @DisplayName("無效的模式值")
        void updateMode_無效模式() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).name("陳哥VIP").tradeMode(SignalSourceConfig.TradeMode.AUTO).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(source));

            String result = service.updateSourceTradeMode("陳哥", "INVALID");

            assertThat(result).contains("不支援");
            assertThat(result).contains("AUTO");
            assertThat(result).contains("SHADOW");
        }

        @Test
        @DisplayName("已是相同模式")
        void updateMode_相同模式不修改() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).name("陳哥VIP").tradeMode(SignalSourceConfig.TradeMode.SHADOW).build();
            when(signalSourceConfigRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(source));

            String result = service.updateSourceTradeMode("陳哥", "SHADOW");

            assertThat(result).contains("已是 SHADOW");
        }
    }

    @Nested
    @DisplayName("日期範圍交易查詢")
    class TradesByDateTests {

        @Test
        @DisplayName("查詢昨天交易 — 有資料回傳明細")
        void getTradesByDate_yesterday_withTrades() {
            Trade trade = Trade.builder()
                    .userId("u1").symbol("BTCUSDT").side("LONG").netProfit(100.0).build();
            User user = User.builder().userId("u1").name("TestUser").build();

            when(tradeRepository.findClosedTradesBetween(any(), any())).thenReturn(List.of(trade));
            when(userRepository.findAll()).thenReturn(List.of(user));

            String result = service.getTradesByDate("yesterday");

            assertThat(result).contains("交易紀錄");
            assertThat(result).contains("BTCUSDT");
            assertThat(result).contains("TestUser");
        }

        @Test
        @DisplayName("查詢今天交易 — 無資料")
        void getTradesByDate_today_noTrades() {
            when(tradeRepository.findClosedTradesBetween(any(), any())).thenReturn(Collections.emptyList());

            String result = service.getTradesByDate("today");

            assertThat(result).contains("沒有已平倉的交易");
        }

        @Test
        @DisplayName("查詢 7d — 回傳近 7 天")
        void getTradesByDate_7d() {
            when(tradeRepository.findClosedTradesBetween(any(), any())).thenReturn(Collections.emptyList());

            String result = service.getTradesByDate("7d");

            assertThat(result).contains("近 7 天");
        }

        @Test
        @DisplayName("無效日期格式 — 回傳錯誤訊息")
        void getTradesByDate_invalidFormat() {
            String result = service.getTradesByDate("abc123");

            assertThat(result).contains("無法解析日期");
        }

        @Test
        @DisplayName("YYYY-MM-DD 格式 — 正常查詢")
        void getTradesByDate_isoFormat() {
            when(tradeRepository.findClosedTradesBetween(any(), any())).thenReturn(Collections.emptyList());

            String result = service.getTradesByDate("2026-03-18");

            assertThat(result).contains("2026-03-18");
        }
    }

    @Nested
    @DisplayName("getUserBalance — 單用戶即時餘額")
    class GetUserBalanceTests {

        @org.junit.jupiter.api.Test
        @DisplayName("用戶有 API Key → 從 Binance 拿到實際餘額")
        void userWithApiKeyReturnsRealBalance() {
            String userId = "user_001";
            UserApiKeyService.BinanceKeys keys = new UserApiKeyService.BinanceKeys("api_k", "sec_k");
            org.mockito.Mockito.when(userApiKeyService.getUserBinanceKeys(userId))
                    .thenReturn(java.util.Optional.of(keys));
            org.mockito.Mockito.when(binanceFuturesService.getAvailableBalance())
                    .thenReturn(1234.56);

            String result = service.getUserBalance(userId);

            org.assertj.core.api.Assertions.assertThat(result).contains("1234.56");
            org.assertj.core.api.Assertions.assertThat(result).contains("USDT");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("用戶沒 API Key → 回友善訊息，不打 Binance")
        void userWithoutApiKeyReturnsFriendlyMessage() {
            String userId = "user_no_key";
            org.mockito.Mockito.when(userApiKeyService.getUserBinanceKeys(userId))
                    .thenReturn(java.util.Optional.empty());

            String result = service.getUserBalance(userId);

            org.assertj.core.api.Assertions.assertThat(result).contains("未設定");
            org.mockito.Mockito.verify(binanceFuturesService, org.mockito.Mockito.never())
                    .getAvailableBalance();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("Binance API 失敗 → 不拋出，回錯誤訊息")
        void binanceFailureReturnsErrorMessage() {
            String userId = "user_001";
            UserApiKeyService.BinanceKeys keys = new UserApiKeyService.BinanceKeys("api_k", "sec_k");
            org.mockito.Mockito.when(userApiKeyService.getUserBinanceKeys(userId))
                    .thenReturn(java.util.Optional.of(keys));
            org.mockito.Mockito.when(binanceFuturesService.getAvailableBalance())
                    .thenThrow(new RuntimeException("API key invalid"));

            String result = service.getUserBalance(userId);

            org.assertj.core.api.Assertions.assertThat(result).contains("查詢失敗");
        }
    }

    @Nested
    @DisplayName("getAllUserBalances — 全用戶即時餘額")
    class GetAllUserBalancesTests {

        @org.junit.jupiter.api.Test
        @DisplayName("3 個用戶各有 API Key → 全部餘額正確聚合")
        void threeUsersAllSucceed() {
            UserApiKeyService.BinanceKeys k1 = new UserApiKeyService.BinanceKeys("a1", "s1");
            UserApiKeyService.BinanceKeys k2 = new UserApiKeyService.BinanceKeys("a2", "s2");
            UserApiKeyService.BinanceKeys k3 = new UserApiKeyService.BinanceKeys("a3", "s3");
            java.util.Map<String, UserApiKeyService.BinanceKeys> keys = new java.util.LinkedHashMap<>();
            keys.put("u1", k1);
            keys.put("u2", k2);
            keys.put("u3", k3);
            org.mockito.Mockito.when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(keys);

            // Binance 對 3 個 ThreadLocal 上下文回不同數字
            org.mockito.Mockito.when(binanceFuturesService.getAvailableBalance())
                    .thenReturn(100.0, 200.5, 50.25);

            // 用戶名稱對照
            com.trader.user.entity.User user1 = new com.trader.user.entity.User();
            user1.setUserId("u1"); user1.setName("Alice");
            com.trader.user.entity.User user2 = new com.trader.user.entity.User();
            user2.setUserId("u2"); user2.setName("Bob");
            com.trader.user.entity.User user3 = new com.trader.user.entity.User();
            user3.setUserId("u3"); user3.setName("Carol");
            org.mockito.Mockito.when(userRepository.findAll())
                    .thenReturn(java.util.List.of(user1, user2, user3));

            String result = service.getAllUserBalances();

            org.assertj.core.api.Assertions.assertThat(result).contains("Alice");
            org.assertj.core.api.Assertions.assertThat(result).contains("Bob");
            org.assertj.core.api.Assertions.assertThat(result).contains("Carol");
            org.assertj.core.api.Assertions.assertThat(result).contains("100.00");
            org.assertj.core.api.Assertions.assertThat(result).contains("200.50");
            org.assertj.core.api.Assertions.assertThat(result).contains("50.25");
            // 總計應該出現
            org.assertj.core.api.Assertions.assertThat(result).contains("總餘額");
            org.assertj.core.api.Assertions.assertThat(result).contains("350.75");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("某用戶 Binance 失敗 → 標記但不影響其他用戶")
        void oneUserFailsOthersStillWork() {
            UserApiKeyService.BinanceKeys k1 = new UserApiKeyService.BinanceKeys("a1", "s1");
            UserApiKeyService.BinanceKeys k2 = new UserApiKeyService.BinanceKeys("a2", "s2");
            java.util.Map<String, UserApiKeyService.BinanceKeys> keys = new java.util.LinkedHashMap<>();
            keys.put("u1", k1);
            keys.put("u2", k2);
            org.mockito.Mockito.when(userApiKeyService.getAllBinanceKeys("BINANCE")).thenReturn(keys);

            // 第一次 OK 第二次拋
            org.mockito.Mockito.when(binanceFuturesService.getAvailableBalance())
                    .thenReturn(100.0)
                    .thenThrow(new RuntimeException("API key revoked"));

            com.trader.user.entity.User user1 = new com.trader.user.entity.User();
            user1.setUserId("u1"); user1.setName("Alice");
            com.trader.user.entity.User user2 = new com.trader.user.entity.User();
            user2.setUserId("u2"); user2.setName("Bob");
            org.mockito.Mockito.when(userRepository.findAll())
                    .thenReturn(java.util.List.of(user1, user2));

            String result = service.getAllUserBalances();

            org.assertj.core.api.Assertions.assertThat(result).contains("Alice");
            org.assertj.core.api.Assertions.assertThat(result).contains("100.00");
            org.assertj.core.api.Assertions.assertThat(result).contains("Bob");
            // Bob 該行應有「查詢失敗」字樣
            org.assertj.core.api.Assertions.assertThat(result).contains("查詢失敗");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("沒有任何用戶有 API Key → 回友善訊息")
        void noUsersWithApiKey() {
            org.mockito.Mockito.when(userApiKeyService.getAllBinanceKeys("BINANCE"))
                    .thenReturn(java.util.Map.of());

            String result = service.getAllUserBalances();

            org.assertj.core.api.Assertions.assertThat(result).contains("無任何用戶");
        }
    }

    @Nested
    @DisplayName("getAllUsersSummary(period) — 帶時間區間的全用戶 PnL")
    class GetAllUsersSummaryWithPeriodTests {

        @org.junit.jupiter.api.Test
        @DisplayName("period=7d → 呼叫 aggregateStatsPerUserSince 並非 aggregateStatsPerUser")
        void periodSevenDaysCallsSinceQuery() {
            Object[] row = new Object[]{"u1", 5L, 3L, 250.0};
            org.mockito.Mockito.when(tradeRepository.aggregateStatsPerUserSince(
                    org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                    .thenReturn(java.util.List.<Object[]>of(row));
            org.mockito.Mockito.when(tradeRepository.findByStatus("OPEN"))
                    .thenReturn(java.util.List.of());
            com.trader.user.entity.User user1 = new com.trader.user.entity.User();
            user1.setUserId("u1"); user1.setName("Alice");
            org.mockito.Mockito.when(userRepository.findAll())
                    .thenReturn(java.util.List.of(user1));

            String result = service.getAllUsersSummary("7d");

            org.assertj.core.api.Assertions.assertThat(result).contains("Alice");
            org.assertj.core.api.Assertions.assertThat(result).contains("250.00");
            org.assertj.core.api.Assertions.assertThat(result).contains("近7天");
            org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.atLeastOnce())
                    .aggregateStatsPerUserSince(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.never())
                    .aggregateStatsPerUser();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("period=all → fallback 到原 aggregateStatsPerUser 行為")
        void periodAllCallsAllTimeQuery() {
            Object[] row = new Object[]{"u1", 50L, 30L, 5000.0};
            org.mockito.Mockito.when(tradeRepository.aggregateStatsPerUser())
                    .thenReturn(java.util.List.<Object[]>of(row));
            org.mockito.Mockito.when(tradeRepository.findByStatus("OPEN"))
                    .thenReturn(java.util.List.of());
            com.trader.user.entity.User user1 = new com.trader.user.entity.User();
            user1.setUserId("u1"); user1.setName("Alice");
            org.mockito.Mockito.when(userRepository.findAll())
                    .thenReturn(java.util.List.of(user1));

            String result = service.getAllUsersSummary("all");

            org.assertj.core.api.Assertions.assertThat(result).contains("Alice");
            org.assertj.core.api.Assertions.assertThat(result).contains("5000.00");
            org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.atLeastOnce())
                    .aggregateStatsPerUser();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("無參數版本 = period=all（向後相容）")
        void backwardCompatibleNoArgsEqualsAllTime() {
            org.mockito.Mockito.when(tradeRepository.aggregateStatsPerUser())
                    .thenReturn(java.util.List.of());
            org.mockito.Mockito.when(tradeRepository.findByStatus("OPEN"))
                    .thenReturn(java.util.List.of());
            org.mockito.Mockito.when(userRepository.findAll()).thenReturn(java.util.List.of());

            String resultOld = service.getAllUsersSummary();
            String resultNew = service.getAllUsersSummary("all");

            // 兩者都應該是「無資料」訊息（or 兩個結果一致）
            org.assertj.core.api.Assertions.assertThat(resultOld).isEqualTo(resultNew);
        }
    }

    @Nested
    @DisplayName("getTodaySignalSummaryWithOutcomes — 今天訊號狀況含廣播結果")
    class GetTodaySignalSummaryWithOutcomesTests {

        @org.junit.jupiter.api.Test
        @DisplayName("有日報 + 有廣播 → 顯示訊號數 + 成功/失敗/跳過")
        void hasReportAndBroadcasts() {
            java.time.LocalDate today = java.time.LocalDate.now();

            com.trader.trading.entity.DailySignalReport report =
                    new com.trader.trading.entity.DailySignalReport();
            report.setReportDate(today);
            report.setTotalSignals(15);
            report.setLongCount(5);
            report.setShortCount(10);
            report.setAvgConfidence(78.0);
            report.setTotalSources(3);
            org.mockito.Mockito.when(dailySignalReportRepository.findByReportDate(today))
                    .thenReturn(java.util.Optional.of(report));

            com.trader.trading.entity.BroadcastLog log1 =
                    com.trader.trading.entity.BroadcastLog.builder()
                            .signalAction("ENTRY").symbol("BTCUSDT")
                            .totalUsers(10).successCount(8).failCount(1)
                            .skippedNoSub(0).skippedNoKey(1).skippedNotAssigned(0)
                            .status("COMPLETED")
                            .build();
            com.trader.trading.entity.BroadcastLog log2 =
                    com.trader.trading.entity.BroadcastLog.builder()
                            .signalAction("CLOSE").symbol("BTCUSDT")
                            .totalUsers(8).successCount(8).failCount(0)
                            .skippedNoSub(0).skippedNoKey(0).skippedNotAssigned(0)
                            .status("COMPLETED")
                            .build();
            org.mockito.Mockito.when(broadcastLogRepository.findByCreatedAtBetween(
                    org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                    org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                    .thenReturn(java.util.List.of(log1, log2));

            String result = service.getTodaySignalSummaryWithOutcomes();

            org.assertj.core.api.Assertions.assertThat(result).contains("15"); // total signals
            org.assertj.core.api.Assertions.assertThat(result).contains("5L"); // long
            org.assertj.core.api.Assertions.assertThat(result).contains("10S"); // short
            org.assertj.core.api.Assertions.assertThat(result).contains("78"); // confidence
            org.assertj.core.api.Assertions.assertThat(result).contains("廣播"); // broadcast section
            org.assertj.core.api.Assertions.assertThat(result).contains("成功");
            org.assertj.core.api.Assertions.assertThat(result).contains("16"); // total success = 8+8
            org.assertj.core.api.Assertions.assertThat(result).contains("失敗");
            org.assertj.core.api.Assertions.assertThat(result).contains("跳過");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("無日報無廣播 → 友善提示「今日無資料」")
        void noDataFriendlyMessage() {
            org.mockito.Mockito.when(dailySignalReportRepository.findByReportDate(
                    org.mockito.ArgumentMatchers.any(java.time.LocalDate.class)))
                    .thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(broadcastLogRepository.findByCreatedAtBetween(
                    org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                    org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                    .thenReturn(java.util.List.of());

            String result = service.getTodaySignalSummaryWithOutcomes();

            org.assertj.core.api.Assertions.assertThat(result).contains("今日");
            org.assertj.core.api.Assertions.assertThat(result).containsAnyOf("無資料", "無訊號");
        }
    }
}
