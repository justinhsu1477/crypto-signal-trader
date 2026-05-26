package com.trader.papertrade.service;

import com.trader.papertrade.dto.SourcePerformanceMetrics;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paper trading 績效分析 — 純 read-only，計算 per-source Sharpe / DD / Profit Factor。
 *
 * <h3>計算流程</h3>
 * <ol>
 *   <li>從 trades 表撈所有 simulated=true + status=CLOSED 的 paper trades</li>
 *   <li>依 source_channel_id 分組</li>
 *   <li>每組計算：基本統計 + Sharpe + Max DD + Profit Factor + Expectancy</li>
 *   <li>JOIN signal_sources 補 display_name / source_id</li>
 * </ol>
 *
 * <h3>與 real trading 隔離保證</h3>
 * 所有 query 都強制 {@code simulated = true}。Service 不寫任何資料、不呼叫 BinanceFuturesService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperPerformanceService {

    private final TradeRepository tradeRepository;
    private final SignalSourceConfigRepository signalSourceRepository;

    /** Sharpe ratio 年化所用交易日數（加密貨幣 24/7，用 365）。 */
    private static final double TRADING_DAYS_PER_YEAR = 365.0;

    /**
     * 取所有 source 的 paper performance metrics（含沒 enabled 的）。
     *
     * <p>排序：按 totalPnl 由高到低（最賺的在前）。
     */
    public List<SourcePerformanceMetrics> getAllSourceMetrics() {
        // 1. 撈所有 closed paper trades
        List<Trade> allClosed = tradeRepository.findClosedPaperTradesGroupedBySource();
        if (allClosed.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 撈 source metadata（display_name 等）— 一次 query 避免 N+1
        Map<String, SignalSourceConfig> sourceByChannelId = signalSourceRepository.findAll().stream()
                .filter(s -> s.getChannelId() != null && !s.getChannelId().isBlank())
                .collect(Collectors.toMap(SignalSourceConfig::getChannelId, s -> s, (a, b) -> a));

        // 3. group by source_channel_id
        Map<String, List<Trade>> tradesByChannel = allClosed.stream()
                .collect(Collectors.groupingBy(Trade::getSourceChannelId));

        // 4. 每組計算 metrics
        return tradesByChannel.entrySet().stream()
                .map(e -> calculateMetrics(e.getKey(), e.getValue(), sourceByChannelId.get(e.getKey())))
                .sorted(Comparator.comparingDouble(SourcePerformanceMetrics::getTotalPnl).reversed())
                .toList();
    }

    /**
     * 取單一 source 的 paper performance metrics。
     *
     * @return null 如果該 source 沒任何 closed paper trade
     */
    public SourcePerformanceMetrics getSourceMetrics(String channelId) {
        if (channelId == null || channelId.isBlank()) return null;
        List<Trade> trades = tradeRepository.findClosedPaperTradesForSource(channelId);
        if (trades.isEmpty()) return null;
        SignalSourceConfig source = signalSourceRepository.findByChannelId(channelId).orElse(null);
        return calculateMetrics(channelId, trades, source);
    }

    // ==================== 核心計算邏輯 ====================

    /**
     * Given closed trades for ONE source (sorted by exitTime ASC), compute all metrics.
     *
     * <p>Visible for testing.
     */
    SourcePerformanceMetrics calculateMetrics(String channelId, List<Trade> trades,
                                              SignalSourceConfig source) {
        int n = trades.size();
        int wins = 0;
        int losses = 0;
        double totalPnl = 0;
        double sumWins = 0;
        double sumLosses = 0;  // 累積為負數

        // 用 exitTime 算期間 — sorted by exitTime ASC
        LocalDateTime firstExit = trades.get(0).getExitTime();
        LocalDateTime lastExit = trades.get(n - 1).getExitTime();

        for (Trade t : trades) {
            double pnl = t.getNetProfit() != null ? t.getNetProfit() : 0;
            totalPnl += pnl;
            if (pnl > 0) {
                wins++;
                sumWins += pnl;
            } else if (pnl < 0) {
                losses++;
                sumLosses += pnl;
            }
        }

        double winRate = n > 0 ? (double) wins / n : 0;
        double avgPnl = n > 0 ? totalPnl / n : 0;
        double avgWin = wins > 0 ? sumWins / wins : 0;
        double avgLoss = losses > 0 ? sumLosses / losses : 0;  // negative

        // Profit Factor：sum(wins) / |sum(losses)|；無 loss 時用 NaN→無限大 處理
        double profitFactor;
        if (sumLosses == 0) {
            profitFactor = wins > 0 ? Double.POSITIVE_INFINITY : 0;
        } else {
            profitFactor = sumWins / Math.abs(sumLosses);
        }

        // Expectancy: winRate × avgWin + (1-winRate) × avgLoss
        double expectancy = winRate * avgWin + (1 - winRate) * avgLoss;

        // Max Drawdown — 走 equity curve 找最大 peak-to-trough
        double maxDdPct = computeMaxDrawdown(trades);

        // Sharpe Ratio — 用每筆 PnL 作 return（簡化），annualized
        double sharpe = computeSharpeRatio(trades, firstExit, lastExit);

        long periodDays = firstExit != null && lastExit != null
                ? Math.max(1, Duration.between(firstExit, lastExit).toDays())
                : 0;

        return SourcePerformanceMetrics.builder()
                .sourceId(source != null ? source.getId() : null)
                .sourceName(source != null ? source.getName() : null)
                .displayName(source != null ? source.getDisplayName() : "(unknown)")
                .channelId(channelId)
                .closedTrades(n)
                .wins(wins)
                .losses(losses)
                .winRate(round4(winRate))
                .totalPnl(round2(totalPnl))
                .avgPnl(round2(avgPnl))
                .avgWin(round2(avgWin))
                .avgLoss(round2(avgLoss))
                .profitFactor(profitFactor == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : round4(profitFactor))
                .maxDrawdownPct(round4(maxDdPct))
                .sharpeRatio(round4(sharpe))
                .expectancy(round2(expectancy))
                .firstTradeAt(firstExit)
                .lastTradeAt(lastExit)
                .periodDays(periodDays)
                .build();
    }

    /**
     * Max Drawdown — 走過 equity curve，記錄 peak 高點與目前 equity 的最大 drawdown。
     *
     * <p>DD% = (peak - trough) / peak。0.20 表示從高點跌 20%。
     *
     * <p>若從未獲利（peak 從未超過 0）→ 回傳 0（沒「peak」可定義 drawdown）。
     * 此情況下用 totalPnl 看「整體虧多少」較合適。
     */
    private double computeMaxDrawdown(List<Trade> trades) {
        double equity = 0;
        double peak = 0;
        double maxDdAbsolute = 0;
        boolean hadPositivePeak = false;

        for (Trade t : trades) {
            double pnl = t.getNetProfit() != null ? t.getNetProfit() : 0;
            equity += pnl;
            if (equity > peak) {
                peak = equity;
                if (peak > 0) hadPositivePeak = true;
            }
            double dd = peak - equity;
            if (dd > maxDdAbsolute) {
                maxDdAbsolute = dd;
            }
        }

        // 從未到達正 equity → 沒 peak 概念 → DD = 0
        // (此情況 totalPnl 已能呈現「整體虧多少」)
        if (!hadPositivePeak || peak <= 0) {
            return 0;
        }
        return maxDdAbsolute / peak;
    }

    /**
     * Sharpe Ratio (annualized) — 簡化版用每筆 PnL 當「return per trade」。
     *
     * <p>公式: <code>avg(return) / stddev(return) × sqrt(trades_per_year)</code>
     * 其中 trades_per_year = (365 / period_days) × n_trades
     *
     * <p>若 stddev 為 0（所有 trade 等值）或 n < 2 → 回傳 0
     */
    private double computeSharpeRatio(List<Trade> trades, LocalDateTime first, LocalDateTime last) {
        if (trades.size() < 2 || first == null || last == null) return 0;

        double mean = trades.stream().mapToDouble(t -> t.getNetProfit() != null ? t.getNetProfit() : 0)
                .average().orElse(0);

        double variance = trades.stream()
                .mapToDouble(t -> {
                    double p = t.getNetProfit() != null ? t.getNetProfit() : 0;
                    return Math.pow(p - mean, 2);
                })
                .sum() / (trades.size() - 1);  // sample variance

        double stddev = Math.sqrt(variance);
        if (stddev == 0) return 0;

        // 年化倍數 = trades_per_year = (365 / period_days) * n_trades
        long periodDays = Math.max(1, Duration.between(first, last).toDays());
        double tradesPerYear = (TRADING_DAYS_PER_YEAR / periodDays) * trades.size();

        return (mean / stddev) * Math.sqrt(tradesPerYear);
    }

    private static double round2(double v) {
        if (!Double.isFinite(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        if (!Double.isFinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
