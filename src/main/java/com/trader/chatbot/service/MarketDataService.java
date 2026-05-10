package com.trader.chatbot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.dto.signalsource.UpdateSignalSourceRequest;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.DailySignalReportRepository;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.SignalSourceService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.trader.shared.config.AppConstants;

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
    private final UserRepository userRepository;
    private final SignalSourceConfigRepository signalSourceConfigRepository;
    private final BroadcastLogRepository broadcastLogRepository;
    private final SignalSourceService signalSourceService;
    private final OkHttpClient okHttpClient;
    private final UserApiKeyService userApiKeyService;
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
     * 查詢單一用戶的即時 Binance USDT 餘額
     *
     * 線程安全：使用 BinanceFuturesService.setCurrentUserKeys/clearCurrentUserKeys 包裝，
     * 確保 ThreadLocal 不會洩漏到下次呼叫。
     *
     * @param userId 用戶 ID
     * @return 格式化字串，含餘額或錯誤訊息（不拋出）
     */
    public String getUserBalance(String userId) {
        var keysOpt = userApiKeyService.getUserBinanceKeys(userId);
        if (keysOpt.isEmpty()) {
            return "用戶 " + userId + " 未設定 Binance API Key，無法查詢餘額。";
        }

        try {
            BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
            try {
                double balance = binanceFuturesService.getAvailableBalance();
                return String.format("用戶 %s 即時餘額：%.2f USDT", userId, balance);
            } finally {
                BinanceFuturesService.clearCurrentUserKeys();
            }
        } catch (Exception e) {
            log.warn("查詢用戶 {} 餘額失敗: {}", userId, e.getMessage());
            return String.format("用戶 %s 餘額查詢失敗：%s", userId, e.getMessage());
        }
    }

    /**
     * 查詢全部用戶的即時 Binance USDT 餘額
     *
     * 設計：
     * - 從 UserApiKeyService 拿全部用戶的解密 API Key（一次 SQL）
     * - 對每個用戶：set ThreadLocal → 呼叫 getAvailableBalance → clear ThreadLocal
     * - 個別失敗不影響整體，標記為「查詢失敗」
     * - 結果聚合成 Markdown，回給 Gemini 格式化成 Discord 訊息
     *
     * 注意：第一版採序列呼叫；高併發時可改 CompletableFuture 並行（最多 5 並發）。
     *
     * @return Markdown 格式字串
     */
    public String getAllUserBalances() {
        StringBuilder sb = new StringBuilder("### 全用戶即時餘額（Binance API）\n");

        java.util.Map<String, UserApiKeyService.BinanceKeys> allKeys =
                userApiKeyService.getAllBinanceKeys("BINANCE");

        if (allKeys.isEmpty()) {
            sb.append("- 目前無任何用戶設定 Binance API Key\n");
            return sb.toString();
        }

        // 用戶名稱對照表
        java.util.Map<String, String> userNames = userRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.trader.user.entity.User::getUserId,
                        u -> u.getName() != null && !u.getName().isEmpty()
                                ? u.getName()
                                : (u.getEmail() != null ? u.getEmail() : u.getUserId()),
                        (a, b) -> a
                ));

        double totalBalance = 0.0;
        int successCount = 0;
        int failCount = 0;

        for (var entry : allKeys.entrySet()) {
            String uid = entry.getKey();
            String name = userNames.getOrDefault(uid, uid);

            try {
                BinanceFuturesService.setCurrentUserKeys(entry.getValue());
                try {
                    double balance = binanceFuturesService.getAvailableBalance();
                    sb.append(String.format("- %s | %.2f USDT\n", name, balance));
                    totalBalance += balance;
                    successCount++;
                } finally {
                    BinanceFuturesService.clearCurrentUserKeys();
                }
            } catch (Exception e) {
                log.warn("查詢用戶 {} 餘額失敗: {}", uid, e.getMessage());
                sb.append(String.format("- %s | [查詢失敗：%s]\n", name, e.getMessage()));
                failCount++;
            }
        }

        sb.append(String.format("\n總用戶：%d | 成功：%d | 失敗：%d | 總餘額：%.2f USDT\n",
                allKeys.size(), successCount, failCount, totalBalance));
        return sb.toString();
    }

    /**
     * 全用戶持倉與交易概覽（Admin 專屬）— 全時間版本（向後相容）
     */
    public String getAllUsersSummary() {
        return getAllUsersSummary("all");
    }

    /**
     * 全用戶持倉與交易概覽（Admin 專屬）— 指定時間區間
     *
     * @param period 時間區間：7d / 30d / 90d / all（預設 all）
     */
    public String getAllUsersSummary(String period) {
        String periodLabel = formatPeriodLabel(period);
        StringBuilder sb = new StringBuilder("### 全用戶持倉與交易概覽（" + periodLabel + "）\n");

        try {
            // 用戶名稱對照表（一次查詢）
            Map<String, String> userNames = userRepository.findAll().stream()
                    .collect(Collectors.toMap(
                            User::getUserId,
                            u -> u.getName() != null && !u.getName().isEmpty() ? u.getName() : u.getEmail(),
                            (a, b) -> a
                    ));

            // 批次聚合統計（一次 SQL）— 依 period 選擇查詢方法
            List<Object[]> stats;
            if ("all".equalsIgnoreCase(period) || period == null || period.isEmpty()) {
                stats = tradeRepository.aggregateStatsPerUser();
            } else {
                java.time.LocalDateTime since = parsePeriod(period);
                stats = tradeRepository.aggregateStatsPerUserSince(since);
            }

            // 全部 OPEN 持倉（一次查詢）— 持倉為「即時」狀態，不受 period 影響
            List<Trade> allOpenTrades = tradeRepository.findByStatus("OPEN");
            Map<String, Long> openCountByUser = allOpenTrades.stream()
                    .collect(Collectors.groupingBy(Trade::getUserId, Collectors.counting()));

            if (stats.isEmpty() && allOpenTrades.isEmpty()) {
                sb.append("- 目前無任何交易資料\n");
                return sb.toString();
            }

            // 按用戶輸出
            for (Object[] row : stats) {
                String userId = (String) row[0];
                long totalTrades = ((Number) row[1]).longValue();
                long wins = ((Number) row[2]).longValue();
                double pnl = ((Number) row[3]).doubleValue();
                double winRate = totalTrades > 0 ? (double) wins / totalTrades * 100 : 0;
                long openCount = openCountByUser.getOrDefault(userId, 0L);

                String name = userNames.getOrDefault(userId, userId);
                sb.append(String.format("- %s | 持倉：%d | 已平倉：%d | 勝率：%.0f%% | PnL：%s%.2f USDT\n",
                        name, openCount, totalTrades, winRate,
                        pnl >= 0 ? "+" : "", pnl));
            }

            // 有持倉但沒有已平倉紀錄的用戶
            for (var entry : openCountByUser.entrySet()) {
                boolean alreadyListed = stats.stream().anyMatch(r -> r[0].equals(entry.getKey()));
                if (!alreadyListed) {
                    String name = userNames.getOrDefault(entry.getKey(), entry.getKey());
                    sb.append(String.format("- %s | 持倉：%d | 已平倉：0 | 勝率：N/A | PnL：0.00 USDT\n",
                            name, entry.getValue()));
                }
            }

            sb.append(String.format("\n總持倉用戶：%d | 總開倉數：%d\n",
                    openCountByUser.size(), allOpenTrades.size()));
        } catch (Exception e) {
            log.warn("取得全用戶概覽失敗: {}", e.getMessage());
            sb.append("- [資料載入失敗]\n");
        }

        return sb.toString();
    }

    private String formatPeriodLabel(String period) {
        if (period == null || "all".equalsIgnoreCase(period)) return "全時間";
        return switch (period.toLowerCase()) {
            case "7d" -> "近7天";
            case "30d" -> "近30天";
            case "90d" -> "近90天";
            default -> period;
        };
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

    // ==================== 訊號來源查詢（Admin 專屬） ====================

    /**
     * 取得所有訊號來源清單
     */
    public String getSourceList() {
        StringBuilder sb = new StringBuilder("### 訊號來源清單\n");

        try {
            List<SignalSourceConfig> sources = signalSourceConfigRepository.findAllByOrderByCreatedAtDesc();
            if (sources.isEmpty()) {
                sb.append("- 目前無訊號來源\n");
                return sb.toString();
            }

            for (SignalSourceConfig s : sources) {
                sb.append(String.format("- %s（%s）| 模式：%s | 狀態：%s",
                        s.getName(),
                        s.getDisplayName() != null ? s.getDisplayName() : "無別名",
                        s.getTradeMode().name(),
                        s.isEnabled() ? "啟用" : "停用"));
                if (s.getRiskMultiplier() != 1.0) {
                    sb.append(String.format(" | 風險倍率：%.1fx", s.getRiskMultiplier()));
                }
                sb.append("\n");
            }

            sb.append(String.format("\n共 %d 個來源\n", sources.size()));
        } catch (Exception e) {
            log.warn("取得訊號來源清單失敗: {}", e.getMessage());
            sb.append("- [資料載入失敗]\n");
        }

        return sb.toString();
    }

    /**
     * 取得指定訊號來源的績效統計
     */
    public String getSourcePerformance(String sourceName, String period) {
        StringBuilder sb = new StringBuilder();

        try {
            SignalSourceConfig source = findSourceByName(sourceName);
            if (source == null) {
                return "找不到名稱包含「" + sourceName + "」的訊號來源。\n" + getAvailableSourceNames();
            }

            sb.append(String.format("### %s 績效統計\n", source.getName()));
            sb.append(String.format("- 模式：%s\n", source.getTradeMode().name()));

            java.time.LocalDateTime since = parsePeriod(period);
            String periodLabel = since == null ? "全部" : period;
            sb.append(String.format("- 期間：%s\n\n", periodLabel));

            // 真實交易績效
            Object[] stats = extractAggregateRow(tradeRepository.getSourcePerformanceStats(
                    source.getChannelId(), source.getGuildId(), since));
            if (stats != null) {
                appendPerformanceStats(sb, stats, "真實交易");
            } else {
                sb.append("**真實交易**：無資料\n");
            }

            // 模擬交易績效（若有）
            Object[] paperStats = extractAggregateRow(tradeRepository.getSourcePaperTradeStats(
                    source.getChannelId(), source.getGuildId(), since));
            if (paperStats != null) {
                sb.append("\n");
                appendPerformanceStats(sb, paperStats, "模擬交易");
            }
        } catch (Exception e) {
            log.warn("取得來源績效失敗: sourceName={} error={}", sourceName, e.getMessage());
            sb.append("- [績效資料載入失敗]\n");
        }

        return sb.toString();
    }

    /**
     * 取得指定訊號來源最近的交易明細
     */
    public String getSourceRecentTrades(String sourceName, int count) {
        StringBuilder sb = new StringBuilder();

        try {
            SignalSourceConfig source = findSourceByName(sourceName);
            if (source == null) {
                return "找不到名稱包含「" + sourceName + "」的訊號來源。\n" + getAvailableSourceNames();
            }

            int limit = Math.min(Math.max(count, 1), 10);
            sb.append(String.format("### %s 最近 %d 筆交易\n", source.getName(), limit));

            List<Trade> trades = tradeRepository.findRecentTradesBySource(
                    source.getChannelId(), source.getGuildId(), PageRequest.of(0, limit));

            if (trades.isEmpty()) {
                sb.append("- 無交易紀錄\n");
                return sb.toString();
            }

            for (Trade t : trades) {
                String typeTag = t.isSimulated() ? "[模擬]" : "[實單]";
                sb.append(String.format("- %s %s %s %s", typeTag, t.getSymbol(), t.getSide(), t.getStatus()));
                if (t.getEntryPrice() != null) {
                    sb.append(String.format(" | 入場：$%.2f", t.getEntryPrice()));
                }
                if (t.getExitPrice() != null && t.getExitPrice() > 0) {
                    sb.append(String.format(" | 出場：$%.2f", t.getExitPrice()));
                }
                if (t.getNetProfit() != null) {
                    sb.append(String.format(" | PnL：%s%.2f",
                            t.getNetProfit() >= 0 ? "+" : "", t.getNetProfit()));
                }
                if (t.getAiConfidence() != null) {
                    sb.append(String.format(" | AI：%d", t.getAiConfidence()));
                }
                if (t.getCreatedAt() != null) {
                    sb.append(String.format(" | %s", t.getCreatedAt().toLocalDate()));
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("取得來源交易明細失敗: sourceName={} error={}", sourceName, e.getMessage());
            sb.append("- [交易資料載入失敗]\n");
        }

        return sb.toString();
    }

    /**
     * 取得最近廣播跟單紀錄
     */
    public String getRecentBroadcasts(String sourceName, int count) {
        StringBuilder sb = new StringBuilder("### 最近廣播紀錄");

        try {
            int limit = Math.min(Math.max(count, 1), 10);

            List<BroadcastLog> logs;
            if (sourceName != null && !sourceName.isBlank()) {
                sb.append(String.format("（%s）\n", sourceName));
                logs = broadcastLogRepository.findBySourceAuthorContainingIgnoreCaseOrderByCreatedAtDesc(
                        sourceName, PageRequest.of(0, limit)).getContent();
            } else {
                sb.append("\n");
                logs = broadcastLogRepository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(0, limit)).getContent();
            }

            if (logs.isEmpty()) {
                sb.append("- 無廣播紀錄\n");
                return sb.toString();
            }

            for (BroadcastLog bl : logs) {
                sb.append(String.format("- %s %s %s | 來源：%s",
                        bl.getSignalAction(),
                        bl.getSymbol() != null ? bl.getSymbol() : "",
                        bl.getSide() != null ? bl.getSide() : "",
                        bl.getSourceAuthor() != null ? bl.getSourceAuthor() : "未知"));
                sb.append(String.format(" | 成功：%d/失敗：%d/跳過：%d",
                        bl.getSuccessCount(), bl.getFailCount(),
                        bl.getSkippedNoSub() + bl.getSkippedNoKey() + bl.getSkippedNotAssigned()));
                if (bl.getAiConfidence() != null) {
                    sb.append(String.format(" | AI：%d", bl.getAiConfidence()));
                }
                if (bl.getCreatedAt() != null) {
                    sb.append(String.format(" | %s",
                            bl.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))));
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("取得廣播紀錄失敗: error={}", e.getMessage());
            sb.append("\n- [廣播資料載入失敗]\n");
        }

        return sb.toString();
    }

    /**
     * 修改訊號來源的交易模式（Admin 專用）
     */
    public String updateSourceTradeMode(String sourceName, String newMode) {
        SignalSourceConfig source = findSourceByName(sourceName);
        if (source == null) {
            return "找不到名稱包含「" + sourceName + "」的訊號來源。\n" + getAvailableSourceNames();
        }

        // 驗證模式值
        String upperMode = newMode.toUpperCase().trim();
        try {
            SignalSourceConfig.TradeMode.valueOf(upperMode);
        } catch (IllegalArgumentException e) {
            return "不支援的交易模式「" + newMode + "」。可用模式：AUTO（自動跟單）、SHADOW（影子模式）、MANUAL（手動）。";
        }

        String oldMode = source.getTradeMode().name();
        if (oldMode.equals(upperMode)) {
            return String.format("「%s」目前已是 %s 模式，無需修改。", source.getName(), oldMode);
        }

        UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder()
                .tradeMode(upperMode)
                .build();
        signalSourceService.updateSource(source.getId(), req);

        log.info("✅ Chatbot 修改來源模式: source={} {} → {}", source.getName(), oldMode, upperMode);
        return String.format("已成功將「%s」的交易模式從 %s 修改為 %s。", source.getName(), oldMode, upperMode);
    }

    // ==================== Private Helpers ====================

    /**
     * 模糊搜尋訊號來源（name 或 displayName 包含關鍵字）
     */
    private SignalSourceConfig findSourceByName(String keyword) {
        List<SignalSourceConfig> sources = signalSourceConfigRepository.findAllByOrderByCreatedAtDesc();
        String lower = keyword.toLowerCase();
        return sources.stream()
                .filter(s -> (s.getName() != null && s.getName().toLowerCase().contains(lower))
                        || (s.getDisplayName() != null && s.getDisplayName().toLowerCase().contains(lower)))
                .findFirst()
                .orElse(null);
    }

    /**
     * 列出可用的來源名稱（找不到時的提示）
     */
    private String getAvailableSourceNames() {
        List<SignalSourceConfig> sources = signalSourceConfigRepository.findAllByOrderByCreatedAtDesc();
        if (sources.isEmpty()) return "目前無訊號來源。";
        StringBuilder sb = new StringBuilder("可用的訊號來源：\n");
        for (SignalSourceConfig s : sources) {
            sb.append(String.format("- %s\n", s.getName()));
        }
        return sb.toString();
    }

    /**
     * 解析時間區間字串
     */
    private java.time.LocalDateTime parsePeriod(String period) {
        if (period == null || period.isBlank() || "all".equalsIgnoreCase(period)) return null;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return switch (period.toLowerCase()) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            default -> null;
        };
    }

    /**
     * 格式化績效統計輸出
     */
    /**
     * 解開 Hibernate 6 aggregate 查詢的 Object[] 結果。
     *
     * Hibernate 6 對 Object[] 回傳型別 + 單 row aggregate 的 native query 有時會包成
     * nested Object[][]（原本是 List<Object[]>，被外層當 Object[] 接收）。
     * 此 helper 把 nested 結構壓平回單 row，並把 tradeCount=0 視為無資料。
     *
     * @return 有效 row；無資料（null / 空 / tradeCount=0）回傳 null
     */
    private Object[] extractAggregateRow(Object[] stats) {
        if (stats == null || stats.length == 0 || stats[0] == null) return null;
        Object[] row = (stats[0] instanceof Object[]) ? (Object[]) stats[0] : stats;
        if (row[0] == null || ((Number) row[0]).longValue() == 0) return null;
        return row;
    }

    private void appendPerformanceStats(StringBuilder sb, Object[] stats, String label) {
        long tradeCount = ((Number) stats[0]).longValue();
        long winCount = ((Number) stats[1]).longValue();
        double totalPnl = ((Number) stats[2]).doubleValue();
        double avgPnl = ((Number) stats[3]).doubleValue();
        double maxWin = ((Number) stats[4]).doubleValue();
        double maxLoss = ((Number) stats[5]).doubleValue();
        double grossWins = ((Number) stats[6]).doubleValue();
        double grossLosses = ((Number) stats[7]).doubleValue();
        double winRate = tradeCount > 0 ? (double) winCount / tradeCount * 100 : 0;
        double profitFactor = grossLosses != 0 ? Math.abs(grossWins / grossLosses) : 0;

        sb.append(String.format("**%s**\n", label));
        sb.append(String.format("- 交易數：%d | 勝率：%.0f%%（%d 勝 / %d 負）\n",
                tradeCount, winRate, winCount, tradeCount - winCount));
        sb.append(String.format("- 總 PnL：%s%.2f USDT | 平均：%s%.2f\n",
                totalPnl >= 0 ? "+" : "", totalPnl,
                avgPnl >= 0 ? "+" : "", avgPnl));
        sb.append(String.format("- 最大獲利：+%.2f | 最大虧損：%.2f\n", maxWin, maxLoss));
        String pfStr = profitFactor == 0 ? "N/A" : String.format("%.2f", profitFactor);
        sb.append(String.format("- Profit Factor：%s\n", pfStr));
    }

    // ═══════════════════════════════════════
    //  日期範圍交易查詢
    // ═══════════════════════════════════════

    /**
     * 查詢指定日期範圍的所有交易紀錄
     *
     * @param dateStr 日期描述（支援：yesterday, today, 7d, 30d, 或 YYYY-MM-DD）
     * @return 格式化的交易明細
     */
    public String getTradesByDate(String dateStr) {
        try {
            LocalDate today = LocalDate.now(AppConstants.ZONE_ID);
            LocalDateTime from;
            LocalDateTime to;
            String label;

            switch (dateStr.toLowerCase().trim()) {
                case "yesterday", "昨天" -> {
                    LocalDate yesterday = today.minusDays(1);
                    from = yesterday.atStartOfDay();
                    to = yesterday.atTime(LocalTime.MAX);
                    label = yesterday.toString();
                }
                case "today", "今天" -> {
                    from = today.atStartOfDay();
                    to = today.atTime(LocalTime.MAX);
                    label = today.toString();
                }
                case "7d", "本週" -> {
                    from = today.minusDays(7).atStartOfDay();
                    to = today.atTime(LocalTime.MAX);
                    label = "近 7 天";
                }
                case "30d", "本月" -> {
                    from = today.minusDays(30).atStartOfDay();
                    to = today.atTime(LocalTime.MAX);
                    label = "近 30 天";
                }
                default -> {
                    // 嘗試解析 YYYY-MM-DD
                    try {
                        LocalDate date = LocalDate.parse(dateStr.trim());
                        from = date.atStartOfDay();
                        to = date.atTime(LocalTime.MAX);
                        label = date.toString();
                    } catch (Exception e) {
                        return "無法解析日期「" + dateStr + "」，支援格式：yesterday / today / 7d / 30d / YYYY-MM-DD";
                    }
                }
            }

            List<Trade> trades = tradeRepository.findClosedTradesBetween(from, to);

            if (trades.isEmpty()) {
                return label + " 沒有已平倉的交易紀錄。";
            }

            // 聚合統計
            long winCount = trades.stream().filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
            double totalPnl = trades.stream().mapToDouble(t -> t.getNetProfit() != null ? t.getNetProfit() : 0).sum();
            double winRate = (double) winCount / trades.size() * 100;

            // 按用戶分組
            Map<String, List<Trade>> byUser = trades.stream()
                    .collect(Collectors.groupingBy(t -> t.getUserId() != null ? t.getUserId() : "unknown"));

            // 用戶名稱對照
            Map<String, String> userNames = userRepository.findAll().stream()
                    .collect(Collectors.toMap(User::getUserId, u -> u.getName() != null ? u.getName() : u.getEmail(), (a, b) -> a));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📊 %s 交易紀錄（共 %d 筆）\n", label, trades.size()));
            sb.append(String.format("勝率：%.0f%%（%d 勝 / %d 負）| 總 PnL：%s%.2f USDT\n\n",
                    winRate, winCount, trades.size() - winCount,
                    totalPnl >= 0 ? "+" : "", totalPnl));

            for (var entry : byUser.entrySet()) {
                String userName = userNames.getOrDefault(entry.getKey(), entry.getKey());
                List<Trade> userTrades = entry.getValue();
                double userPnl = userTrades.stream().mapToDouble(t -> t.getNetProfit() != null ? t.getNetProfit() : 0).sum();

                sb.append(String.format("👤 %s（%d 筆 | %s%.2f USDT）\n",
                        userName, userTrades.size(), userPnl >= 0 ? "+" : "", userPnl));

                for (Trade t : userTrades) {
                    String pnl = t.getNetProfit() != null ? String.format("%s%.2f", t.getNetProfit() >= 0 ? "+" : "", t.getNetProfit()) : "N/A";
                    sb.append(String.format("  - %s %s | PnL: %s USDT\n",
                            t.getSymbol(), t.getSide() != null ? t.getSide() : "", pnl));
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("查詢日期交易失敗: {}", e.getMessage(), e);
            return "查詢失敗：" + e.getMessage();
        }
    }
}
