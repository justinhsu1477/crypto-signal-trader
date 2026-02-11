package com.trader.service;

import com.trader.entity.Trade;
import com.trader.entity.TradeEvent;
import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import com.trader.repository.TradeEventRepository;
import com.trader.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 交易紀錄服務 — 負責將每次操作寫入 H2 資料庫
 *
 * 核心職責:
 * 1. ENTRY 成功時 → 建立 Trade(OPEN) + 入場/止損事件
 * 2. CLOSE 時 → 更新 Trade 為 CLOSED + 計算盈虧
 * 3. MOVE_SL 時 → 寫 Event 紀錄止損變更
 * 4. CANCEL 時 → 更新 Trade 為 CANCELLED
 * 5. FAIL_SAFE 時 → 寫 Event 紀錄安全機制觸發
 * 6. 提供統計摘要查詢
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeRecordService {

    private final TradeRepository tradeRepository;
    private final TradeEventRepository tradeEventRepository;

    // ==================== 寫入操作 ====================

    /**
     * ENTRY 成功：建立一筆 Trade(OPEN) + ENTRY_PLACED 事件 + SL_PLACED 事件
     *
     * @param signal     原始訊號
     * @param entryOrder 入場單結果
     * @param slOrder    止損單結果
     * @param leverage   使用的槓桿
     * @param riskAmount 以損定倉的風險金額
     * @param signalHash 訊號去重雜湊（可為 null）
     * @return tradeId
     */
    @Transactional
    public String recordEntry(TradeSignal signal, OrderResult entryOrder, OrderResult slOrder,
                              int leverage, double riskAmount, String signalHash) {
        String tradeId = UUID.randomUUID().toString();

        // 建立 Trade 主紀錄
        Trade trade = Trade.builder()
                .tradeId(tradeId)
                .symbol(signal.getSymbol())
                .side(signal.getSide().name())
                .entryPrice(entryOrder.getPrice())
                .entryQuantity(entryOrder.getQuantity())
                .entryTime(LocalDateTime.now())
                .entryOrderId(entryOrder.getOrderId())
                .stopLoss(signal.getStopLoss())
                .leverage(leverage)
                .riskAmount(riskAmount)
                .signalHash(signalHash)
                .status("OPEN")
                .build();

        tradeRepository.save(trade);

        // 寫入 ENTRY_PLACED 事件
        saveEvent(tradeId, "ENTRY_PLACED", entryOrder);

        // 寫入 SL_PLACED 事件
        if (slOrder != null) {
            saveEvent(tradeId, "SL_PLACED", slOrder);
        }

        log.info("交易紀錄建立: tradeId={} {} {} entry={} qty={} SL={}",
                tradeId, signal.getSymbol(), signal.getSide(),
                entryOrder.getPrice(), entryOrder.getQuantity(), signal.getStopLoss());

        return tradeId;
    }

    /**
     * CLOSE：更新 Trade 為 CLOSED，計算盈虧
     *
     * @param symbol     交易對
     * @param closeOrder 平倉單結果
     * @param exitReason 出場原因（SIGNAL_CLOSE / STOP_LOSS / MANUAL_CLOSE / FAIL_SAFE）
     */
    @Transactional
    public void recordClose(String symbol, OrderResult closeOrder, String exitReason) {
        Optional<Trade> openTradeOpt = tradeRepository.findOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("找不到 OPEN 狀態的交易紀錄: {}", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();

        // 更新平倉資訊
        trade.setExitPrice(closeOrder.getPrice());
        trade.setExitQuantity(closeOrder.getQuantity());
        trade.setExitTime(LocalDateTime.now());
        trade.setExitOrderId(closeOrder.getOrderId());
        trade.setExitReason(exitReason);
        trade.setStatus("CLOSED");

        // 計算盈虧
        calculateProfit(trade);

        tradeRepository.save(trade);

        // 寫入 CLOSE_PLACED 事件
        saveEvent(trade.getTradeId(), "CLOSE_PLACED", closeOrder);

        log.info("交易平倉紀錄: tradeId={} {} exitPrice={} 淨利={} 原因={}",
                trade.getTradeId(), symbol, closeOrder.getPrice(), trade.getNetProfit(), exitReason);
    }

    /**
     * MOVE_SL：記錄止損移動事件
     *
     * @param symbol   交易對
     * @param slOrder  新的止損單結果
     * @param oldSl    舊止損價
     * @param newSl    新止損價
     */
    @Transactional
    public void recordMoveSL(String symbol, OrderResult slOrder, double oldSl, double newSl) {
        Optional<Trade> openTradeOpt = tradeRepository.findOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("找不到 OPEN 狀態的交易紀錄: {}", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();

        // 更新 Trade 的止損價
        trade.setStopLoss(newSl);
        tradeRepository.save(trade);

        // 寫入 MOVE_SL 事件
        TradeEvent event = TradeEvent.builder()
                .tradeId(trade.getTradeId())
                .eventType("MOVE_SL")
                .binanceOrderId(slOrder.getOrderId())
                .orderSide(slOrder.getSide())
                .orderType(slOrder.getType())
                .price(newSl)
                .quantity(slOrder.getQuantity())
                .success(slOrder.isSuccess())
                .errorMessage(slOrder.isSuccess() ? null : slOrder.getErrorMessage())
                .detail(String.format("{\"old_sl\":%.1f,\"new_sl\":%.1f}", oldSl, newSl))
                .build();

        tradeEventRepository.save(event);

        log.info("止損移動紀錄: tradeId={} {} SL: {} → {}",
                trade.getTradeId(), symbol, oldSl, newSl);
    }

    /**
     * CANCEL：更新 Trade 為 CANCELLED
     *
     * @param symbol 交易對
     */
    @Transactional
    public void recordCancel(String symbol) {
        Optional<Trade> openTradeOpt = tradeRepository.findOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("找不到 OPEN 狀態的交易紀錄: {}", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();
        trade.setStatus("CANCELLED");
        trade.setExitReason("CANCEL");
        tradeRepository.save(trade);

        // 寫入 CANCEL 事件
        TradeEvent event = TradeEvent.builder()
                .tradeId(trade.getTradeId())
                .eventType("CANCEL")
                .success(true)
                .detail("{\"reason\":\"掛單取消\"}")
                .build();

        tradeEventRepository.save(event);

        log.info("交易取消紀錄: tradeId={} {}", trade.getTradeId(), symbol);
    }

    /**
     * FAIL_SAFE：記錄安全機制觸發事件
     *
     * @param symbol 交易對
     * @param detail 觸發詳情
     */
    @Transactional
    public void recordFailSafe(String symbol, String detail) {
        Optional<Trade> openTradeOpt = tradeRepository.findOpenTrade(symbol);
        String tradeId = openTradeOpt.map(Trade::getTradeId).orElse("UNKNOWN");

        TradeEvent event = TradeEvent.builder()
                .tradeId(tradeId)
                .eventType("FAIL_SAFE")
                .success(false)
                .detail(detail)
                .build();

        tradeEventRepository.save(event);

        log.warn("Fail-Safe 紀錄: tradeId={} {} detail={}", tradeId, symbol, detail);
    }

    // ==================== 查詢操作 ====================

    /**
     * 查找目前 OPEN 的交易
     */
    public Optional<Trade> findOpenTrade(String symbol) {
        return tradeRepository.findOpenTrade(symbol);
    }

    /**
     * 依狀態查詢交易
     */
    public List<Trade> findByStatus(String status) {
        return tradeRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * 查詢所有交易（倒序）
     */
    public List<Trade> findAll() {
        return tradeRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 查詢單筆交易
     */
    public Optional<Trade> findById(String tradeId) {
        return tradeRepository.findById(tradeId);
    }

    /**
     * 查詢某筆交易的所有事件
     */
    public List<TradeEvent> findEvents(String tradeId) {
        return tradeEventRepository.findByTradeIdOrderByTimestampAsc(tradeId);
    }

    /**
     * 盈虧統計摘要
     */
    public Map<String, Object> getStatsSummary() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long closedCount = tradeRepository.countClosedTrades();
        long winCount = tradeRepository.countWinningTrades();
        double totalNetProfit = tradeRepository.sumNetProfit();
        double grossWins = tradeRepository.sumGrossWins();
        double grossLosses = tradeRepository.sumGrossLosses();
        double totalCommission = tradeRepository.sumCommission();

        // 勝率
        double winRate = closedCount > 0 ? (double) winCount / closedCount * 100 : 0;

        // Profit Factor = 總獲利 / 總虧損（絕對值）
        double profitFactor = grossLosses > 0 ? grossWins / grossLosses : 0;

        // 平均每筆盈虧
        double avgProfit = closedCount > 0 ? totalNetProfit / closedCount : 0;

        // 目前持倉數
        long openCount = tradeRepository.findByStatus("OPEN").size();

        stats.put("closedTrades", closedCount);        // 已平倉筆數
        stats.put("winningTrades", winCount);           // 獲利筆數
        stats.put("winRate", String.format("%.1f%%", winRate));  // 勝率
        stats.put("totalNetProfit", round2(totalNetProfit));     // 總淨利 (USDT)
        stats.put("grossWins", round2(grossWins));               // 獲利總額
        stats.put("grossLosses", round2(grossLosses));           // 虧損總額
        stats.put("profitFactor", round2(profitFactor));         // Profit Factor
        stats.put("avgProfitPerTrade", round2(avgProfit));       // 平均每筆盈虧
        stats.put("totalCommission", round2(totalCommission));   // 總手續費
        stats.put("openPositions", openCount);                   // 目前持倉數

        return stats;
    }

    // ==================== 內部方法 ====================

    /**
     * 計算盈虧（毛利、手續費、淨利）
     */
    private void calculateProfit(Trade trade) {
        if (trade.getEntryPrice() == null || trade.getExitPrice() == null) {
            return;
        }

        double entry = trade.getEntryPrice();
        double exit = trade.getExitPrice();
        double qty = trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0;

        // 方向因子：LONG → (exit - entry), SHORT → (entry - exit)
        int direction = "LONG".equals(trade.getSide()) ? 1 : -1;
        double grossProfit = (exit - entry) * qty * direction;

        // 手續費估算：Binance taker 費率 0.04%，maker 0.02%
        // 入場 LIMIT (maker) + 出場 LIMIT (maker) ≈ 0.02% × 2 = 0.04%
        double commission = (entry * qty * 0.0002) + (exit * qty * 0.0002);

        double netProfit = grossProfit - commission;

        trade.setGrossProfit(round2(grossProfit));
        trade.setCommission(round2(commission));
        trade.setNetProfit(round2(netProfit));
    }

    /**
     * 寫入通用的 OrderResult 事件
     */
    private void saveEvent(String tradeId, String eventType, OrderResult order) {
        TradeEvent event = TradeEvent.builder()
                .tradeId(tradeId)
                .eventType(eventType)
                .binanceOrderId(order.getOrderId())
                .orderSide(order.getSide())
                .orderType(order.getType())
                .price(order.getPrice())
                .quantity(order.getQuantity())
                .success(order.isSuccess())
                .errorMessage(order.isSuccess() ? null : order.getErrorMessage())
                .build();

        tradeEventRepository.save(event);
    }

    /**
     * 四捨五入到小數點後 2 位
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
