package com.trader.chatbot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.DailySignalReportRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.BinanceFuturesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 市場數據服務 — Chatbot 用
 *
 * 整合 Binance 行情 + Fear & Greed Index + 訊號日報，
 * 以格式化文字回傳給 Gemini context。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final BinanceFuturesService binanceFuturesService;
    private final DailySignalReportRepository dailySignalReportRepository;
    private final TradeRepository tradeRepository;
    private final OkHttpClient okHttpClient;
    private final Gson gson = new Gson();

    private static final String FEAR_GREED_URL = "https://api.alternative.me/fcp/v1/fear-and-greed-index/?limit=1";

    /**
     * 取得 BTC 市場概覽（價格 + 漲跌幅 + 成交量 + 資金費率 + 恐懼貪婪指數）
     */
    public String getMarketOverview() {
        StringBuilder sb = new StringBuilder();

        // 1. BTC 24h 行情
        try {
            JsonObject ticker = binanceFuturesService.get24hTicker("BTCUSDT");
            double price = ticker.get("lastPrice").getAsDouble();
            double changePercent = ticker.get("priceChangePercent").getAsDouble();
            double high = ticker.get("highPrice").getAsDouble();
            double low = ticker.get("lowPrice").getAsDouble();
            double volume = ticker.get("quoteVolume").getAsDouble();

            sb.append("### BTC 即時行情\n");
            sb.append(String.format("- 價格：$%.2f\n", price));
            sb.append(String.format("- 24h 漲跌：%.2f%%\n", changePercent));
            sb.append(String.format("- 24h 最高/最低：$%.2f / $%.2f\n", high, low));
            sb.append(String.format("- 24h 成交額：$%.0fM\n", volume / 1_000_000));
        } catch (Exception e) {
            log.warn("取得 BTC 行情失敗: {}", e.getMessage());
            sb.append("### BTC 即時行情\n- [資料載入失敗]\n");
        }

        // 2. Funding Rate
        try {
            JsonObject funding = binanceFuturesService.getFundingRate("BTCUSDT");
            if (funding.has("fundingRate")) {
                double rate = funding.get("fundingRate").getAsDouble();
                String sentiment = rate > 0.0001 ? "偏多（多頭付費）" :
                                   rate < -0.0001 ? "偏空（空頭付費）" : "中性";
                sb.append(String.format("\n### 資金費率\n- BTC Funding Rate：%.4f%%（%s）\n",
                        rate * 100, sentiment));
            }
        } catch (Exception e) {
            log.warn("取得 Funding Rate 失敗: {}", e.getMessage());
        }

        // 3. Fear & Greed Index
        try {
            String fgiResult = fetchFearGreedIndex();
            if (fgiResult != null) {
                sb.append("\n").append(fgiResult);
            }
        } catch (Exception e) {
            log.warn("取得恐懼貪婪指數失敗: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 取得恐懼貪婪指數
     */
    String fetchFearGreedIndex() {
        try {
            Request request = new Request.Builder().url(FEAR_GREED_URL).get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;

                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                var data = json.getAsJsonArray("data");
                if (data == null || data.isEmpty()) return null;

                JsonObject latest = data.get(0).getAsJsonObject();
                int value = latest.get("value").getAsInt();
                String classification = latest.get("value_classification").getAsString();

                String emoji = value <= 25 ? "😱" : value <= 45 ? "😨" : value <= 55 ? "😐" : value <= 75 ? "😀" : "🤑";

                return String.format("### 恐懼貪婪指數\n- %s %d/100（%s）\n", emoji, value, classification);
            }
        } catch (Exception e) {
            log.warn("Fear & Greed API 失敗: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 取得用戶目前的持倉狀況
     */
    public String getUserPositions(String userId) {
        StringBuilder sb = new StringBuilder("### 目前持倉\n");

        try {
            List<Trade> openTrades = tradeRepository.findByUserIdAndStatus(userId, "OPEN");
            if (openTrades.isEmpty()) {
                sb.append("- 目前無持倉\n");
                return sb.toString();
            }

            for (Trade t : openTrades) {
                sb.append(String.format("- %s %s | 入場：$%.2f | 數量：%.4f",
                        t.getSymbol(), t.getSide(),
                        t.getEntryPrice() != null ? t.getEntryPrice() : 0.0,
                        t.getEntryQuantity() != null ? t.getEntryQuantity() : 0.0));
                if (t.getStopLoss() != null) {
                    sb.append(String.format(" | SL：$%.2f", t.getStopLoss()));
                }
                if (t.getLeverage() != null) {
                    sb.append(String.format(" | %dx", t.getLeverage()));
                }

                // 計算未實現 PnL
                try {
                    double currentPrice = binanceFuturesService.getMarkPrice(t.getSymbol());
                    double qty = t.getEntryQuantity() != null ? t.getEntryQuantity() : 0;
                    double entry = t.getEntryPrice() != null ? t.getEntryPrice() : 0;
                    double direction = "LONG".equalsIgnoreCase(t.getSide()) ? 1 : -1;
                    double unrealizedPnl = (currentPrice - entry) * qty * direction;
                    sb.append(String.format(" | 未實現 PnL：%s$%.2f",
                            unrealizedPnl >= 0 ? "+" : "", unrealizedPnl));
                } catch (Exception ignored) {
                    // 取不到即時價格就跳過
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("取得用戶持倉失敗: userId={} error={}", userId, e.getMessage());
            sb.append("- [持倉資料載入失敗]\n");
        }

        return sb.toString();
    }

    /**
     * 取得最近訊號日報摘要
     */
    public String getSignalReportSummary() {
        StringBuilder sb = new StringBuilder("### 最近訊號日報\n");

        try {
            // 取最近 3 天的日報
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 3; i++) {
                LocalDate date = today.minusDays(i);
                Optional<DailySignalReport> report = dailySignalReportRepository.findByReportDate(date);
                if (report.isPresent()) {
                    DailySignalReport r = report.get();
                    sb.append(String.format("- %s：%d 條訊號（%dL/%dS）",
                            r.getReportDate(), r.getTotalSignals(),
                            r.getLongCount(), r.getShortCount()));
                    if (r.getAvgConfidence() != null) {
                        sb.append(String.format(" | 平均信心：%.0f/100", r.getAvgConfidence()));
                    }
                    sb.append(String.format(" | 來源：%d 個\n", r.getTotalSources()));
                }
            }

            if (sb.toString().equals("### 最近訊號日報\n")) {
                sb.append("- 近 3 天無日報資料\n");
            }
        } catch (Exception e) {
            log.warn("取得訊號日報失敗: {}", e.getMessage());
            sb.append("- [日報資料載入失敗]\n");
        }

        return sb.toString();
    }
}
