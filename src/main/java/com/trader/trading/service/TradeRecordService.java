package com.trader.trading.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.AppConstants;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 交易紀錄服務 — 負責將每次操作寫入資料庫
 *
 * 多用戶模式（MULTI_USER_ENABLED）：
 * - true  → 用 ThreadLocal userId 隔離查詢，每個用戶只看到自己的 Trade
 * - false → 全局查詢（舊系統行為，所有 Trade 不分用戶）
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
    private final ObjectMapper objectMapper;
    private final MultiUserConfig multiUserConfig;

    // ========== 多用戶支援 ==========
    @Value("${trading.user-id:${user.id:test-user}}")
    private String defaultUserId;  // 從環境變數或配置讀取，測試時預設 test-user

    /** 廣播模式下，BinanceFuturesService 會設定當前用戶 ID */
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    /** 設定當前線程的用戶 ID（供 BinanceFuturesService.executeSignalForBroadcast 呼叫） */
    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    /** 清除當前線程的用戶 ID */
    public static void clearCurrentUserId() {
        CURRENT_USER_ID.remove();
    }

    /** 取得當前線程的用戶 ID（供 TradeConfigResolver 使用） */
    public static String getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 取得當前有效的 userId
     * 優先順序：ThreadLocal（廣播模式）→ defaultUserId（全局設定）
     */
    private String getActiveUserId() {
        String threadUserId = CURRENT_USER_ID.get();
        return threadUserId != null ? threadUserId : defaultUserId;
    }

    /**
     * 查找 OPEN 交易 — 根據 MULTI_USER_ENABLED 決定策略
     * - true：只查當前用戶的（per-user 隔離）
     * - false：全局查詢（舊系統行為）
     */
    private Optional<Trade> resolveOpenTrade(String symbol) {
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            return tradeRepository.findUserOpenTrade(userId, symbol);
        }
        return tradeRepository.findOpenTrade(symbol);
    }

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

        // 入場手續費：優先用 Binance 回傳的真實手續費，fallback 到估算值（maker 0.02%）
        double entryCommission = entryOrder.getCommission() > 0
                ? round2(entryOrder.getCommission())
                : round2(entryOrder.getPrice() * entryOrder.getQuantity() * 0.0002);

        // 止盈目標序列化為 JSON（如有）
        String takeProfitsJson = null;
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            takeProfitsJson = toJson(Map.of("targets", signal.getTakeProfits()));
        }

        // 建立 Trade 主紀錄
        Trade trade = Trade.builder()
                .tradeId(tradeId)
                .userId(getActiveUserId())  // 多用戶模式用 ThreadLocal，否則用全局 defaultUserId
                .symbol(signal.getSymbol())
                .side(signal.getSide().name())
                .entryPrice(entryOrder.getPrice())
                .entryQuantity(entryOrder.getQuantity())
                .entryTime(LocalDateTime.now(AppConstants.ZONE_ID))
                .entryOrderId(entryOrder.getOrderId())
                .stopLoss(signal.getStopLoss())
                .takeProfits(takeProfitsJson)
                .leverage(leverage)
                .riskAmount(riskAmount)
                .entryCommission(entryCommission)
                .signalHash(signalHash)
                .status("OPEN")
                .build();

        // 寫入訊號來源（如果有的話）
        if (signal.getSource() != null) {
            SignalSource src = signal.getSource();
            trade.setSourcePlatform(src.getPlatform());
            trade.setSourceChannelId(src.getChannelId());
            trade.setSourceGuildId(src.getGuildId());
            trade.setSourceAuthorName(src.getAuthorName());
            trade.setSourceMessageId(src.getMessageId());
        }

        tradeRepository.save(trade);

        // 寫入 ENTRY_PLACED 事件
        saveEvent(tradeId, "ENTRY_PLACED", entryOrder);

        // 寫入 SL_PLACED 事件
        if (slOrder != null) {
            saveEvent(tradeId, "SL_PLACED", slOrder);
        }

        log.info("交易紀錄建立: tradeId={} {} {} entry={} qty={} SL={} 入場手續費={} USDT",
                tradeId, signal.getSymbol(), signal.getSide(),
                entryOrder.getPrice(), entryOrder.getQuantity(), signal.getStopLoss(), entryCommission);

        return tradeId;
    }

    /**
     * DCA 補倉：更新現有 Trade 的加權平均入場價、總數量、SL、風險金額、dcaCount
     *
     * @param symbol     交易對
     * @param signal     DCA 訊號
     * @param dcaOrder   補倉掛單結果
     * @param riskAmount 本次 DCA 的風險金額（2R）
     */
    @Transactional
    public void recordDcaEntry(String symbol, TradeSignal signal, OrderResult dcaOrder, double riskAmount) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("DCA 找不到 OPEN 交易: {}, 改為建立新紀錄", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();

        // 計算加權平均入場價
        // 修正：部分平倉後 DCA 應用 remainingQuantity 而非 entryQuantity
        // 避免已平倉數量被重複計入，導致 entryQuantity 膨脹
        double effectiveOldQty = trade.getRemainingQuantity() != null
                ? trade.getRemainingQuantity()
                : (trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0);
        double oldPrice = trade.getEntryPrice() != null ? trade.getEntryPrice() : 0;
        double newQty = dcaOrder.getQuantity();
        double newPrice = dcaOrder.getPrice();
        double totalQty = effectiveOldQty + newQty;
        double avgPrice = totalQty > 0 ? (oldPrice * effectiveOldQty + newPrice * newQty) / totalQty : newPrice;

        // 更新 Trade
        trade.setEntryPrice(round2(avgPrice));
        trade.setEntryQuantity(totalQty);
        // DCA 後重置部分平倉追蹤（新的總倉位基數已更新）
        trade.setRemainingQuantity(null);
        trade.setTotalClosedQuantity(null);
        trade.setDcaCount((trade.getDcaCount() != null ? trade.getDcaCount() : 0) + 1);
        trade.setRiskAmount(round2((trade.getRiskAmount() != null ? trade.getRiskAmount() : 0) + riskAmount));

        // 更新 SL（DCA 訊號帶的新止損）
        if (signal.getNewStopLoss() != null) {
            trade.setStopLoss(signal.getNewStopLoss());
        }

        // 入場手續費累加：優先用 Binance 回傳真實值，fallback 到估算值
        double dcaCommission = dcaOrder.getCommission() > 0
                ? round2(dcaOrder.getCommission())
                : round2(newPrice * newQty * 0.0002);
        double oldCommission = trade.getEntryCommission() != null ? trade.getEntryCommission() : 0;
        trade.setEntryCommission(round2(oldCommission + dcaCommission));

        tradeRepository.save(trade);

        // 寫入 DCA_ENTRY 事件
        saveEvent(trade.getTradeId(), "DCA_ENTRY", dcaOrder);

        log.info("DCA 紀錄更新: tradeId={} {} 均價: {} → {}, 數量: {} → {}, DCA第{}次, 新SL={}",
                trade.getTradeId(), symbol, oldPrice, avgPrice, effectiveOldQty, totalQty,
                trade.getDcaCount(), trade.getStopLoss());
    }

    /**
     * 查詢某幣種目前的 DCA 補倉次數
     */
    public int getDcaCount(String symbol) {
        return tradeRepository.findDcaCountBySymbol(symbol).orElse(0);
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
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("找不到 OPEN 狀態的交易紀錄: {}", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();

        // 更新平倉資訊
        trade.setExitPrice(closeOrder.getPrice());
        trade.setExitQuantity(closeOrder.getQuantity());
        trade.setExitTime(LocalDateTime.now(AppConstants.ZONE_ID));
        trade.setExitOrderId(closeOrder.getOrderId());
        trade.setExitReason(exitReason);
        trade.setStatus("CLOSED");

        // 計算盈虧（優先用 Binance 回傳的真實出場手續費）
        double realExitCommission = closeOrder.getCommission() > 0 ? closeOrder.getCommission() : 0;
        calculateProfit(trade, realExitCommission);

        tradeRepository.save(trade);

        // 寫入 CLOSE_PLACED 事件
        saveEvent(trade.getTradeId(), "CLOSE_PLACED", closeOrder);

        log.info("交易平倉紀錄: tradeId={} {} exitPrice={} 淨利={} 原因={}",
                trade.getTradeId(), symbol, closeOrder.getPrice(), trade.getNetProfit(), exitReason);
    }

    /**
     * PARTIAL CLOSE：部分平倉，Trade 維持 OPEN 狀態
     * 記錄已平數量，但不結束交易，讓後續 MOVE_SL / CLOSE 訊號能繼續操作
     *
     * @param symbol     交易對
     * @param closeOrder 平倉單結果
     * @param closeRatio 平倉比例 (0.5=平一半)
     * @param exitReason 出場原因
     */
    @Transactional
    public void recordPartialClose(String symbol, OrderResult closeOrder, double closeRatio, String exitReason) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("找不到 OPEN 狀態的交易紀錄: {}", symbol);
            return;
        }

        Trade trade = openTradeOpt.get();

        // 累加已平倉數量
        double closedQty = closeOrder.getQuantity();
        double prevClosed = trade.getTotalClosedQuantity() != null ? trade.getTotalClosedQuantity() : 0;
        trade.setTotalClosedQuantity(prevClosed + closedQty);

        // 計算剩餘數量
        double entryQty = trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0;
        trade.setRemainingQuantity(entryQty - (prevClosed + closedQty));

        // 記錄最近一次部分平倉的價格（不設 status=CLOSED）
        trade.setExitPrice(closeOrder.getPrice());
        trade.setExitQuantity(closedQty);
        trade.setExitOrderId(closeOrder.getOrderId());
        trade.setExitReason(exitReason + "_PARTIAL");
        // ⚠️ 關鍵：維持 OPEN，不設 CLOSED
        trade.setUpdatedAt(LocalDateTime.now(AppConstants.ZONE_ID));

        tradeRepository.save(trade);

        // 寫入 PARTIAL_CLOSE 事件
        saveEvent(trade.getTradeId(), "PARTIAL_CLOSE", closeOrder);

        log.info("部分平倉紀錄: tradeId={} {} ratio={} closedQty={} remaining={} 原因={}",
                trade.getTradeId(), symbol, closeRatio, closedQty,
                trade.getRemainingQuantity(), exitReason);
    }

    /**
     * 查詢某交易對 OPEN 中的開倉價（用於成本保護時當作 SL）
     */
    public Double getEntryPrice(String symbol) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        return openTradeOpt.map(Trade::getEntryPrice).orElse(null);
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
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
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
                .detail(toJson(Map.of("old_sl", oldSl, "new_sl", newSl)))
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
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
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
                .detail(toJson(Map.of("reason", "掛單取消")))
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
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
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

    /**
     * 通用事件紀錄 — 記錄任何訂單操作的結果（成功或失敗）
     * 適用於不需要更動 Trade 主紀錄，只需新增 TradeEvent 的場景
     *
     * @param symbol    交易對（用於查找 tradeId；若無 OPEN Trade 則用 "UNKNOWN"）
     * @param eventType 事件類型（如 ENTRY_FAILED, CLOSE_FAILED, TP_PLACED 等）
     * @param order     OrderResult（可為 null，null 時只記 eventType + detail）
     * @param detail    補充描述（JSON 或文字，可為 null）
     */
    @Transactional
    public void recordOrderEvent(String symbol, String eventType, OrderResult order, String detail) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        String tradeId = openTradeOpt.map(Trade::getTradeId).orElse("UNKNOWN");

        TradeEvent.TradeEventBuilder builder = TradeEvent.builder()
                .tradeId(tradeId)
                .eventType(eventType)
                .detail(detail);

        if (order != null) {
            builder.binanceOrderId(order.getOrderId())
                   .orderSide(order.getSide())
                   .orderType(order.getType())
                   .price(order.getPrice())
                   .quantity(order.getQuantity())
                   .success(order.isSuccess())
                   .errorMessage(order.isSuccess() ? null : order.getErrorMessage());
        } else {
            builder.success(false);
        }

        tradeEventRepository.save(builder.build());

        if (order != null && order.isSuccess()) {
            log.info("事件紀錄: tradeId={} {} {} orderId={}", tradeId, symbol, eventType, order.getOrderId());
        } else {
            log.warn("事件紀錄: tradeId={} {} {} error={}", tradeId, symbol, eventType,
                    order != null ? order.getErrorMessage() : "N/A");
        }
    }

    // ==================== 查詢操作 ====================

    /**
     * 查找目前 OPEN 的交易（對外 API，根據多用戶開關自動隔離）
     */
    public Optional<Trade> findOpenTrade(String symbol) {
        return resolveOpenTrade(symbol);
    }

    /**
     * 查詢所有 OPEN 交易（不限幣種）
     * 用於 CLOSE/MOVE_SL 訊號的 symbol fallback：
     * 如果指定 symbol 無持倉，嘗試找其他 OPEN trade
     */
    public List<Trade> findAllOpenTrades() {
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            return tradeRepository.findByUserIdAndStatus(userId, "OPEN");
        }
        return tradeRepository.findAllOpenTrades();
    }

    /**
     * 依狀態查詢交易
     */
    public List<Trade> findByStatus(String status) {
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            return tradeRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }
        return tradeRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * 查詢所有交易（倒序）
     */
    public List<Trade> findAll() {
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            return tradeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }
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
     * 查詢今日已實現虧損（回傳負數表示虧損）
     * 用於每日虧損熔斷機制：當 |todayLoss| >= maxDailyLoss 時拒絕新交易
     */
    public double getTodayRealizedLoss() {
        LocalDateTime startOfToday = LocalDateTime.now(AppConstants.ZONE_ID).toLocalDate().atStartOfDay();
        List<Trade> closedToday;
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            closedToday = tradeRepository.findUserClosedTradesAfter(userId, startOfToday);
        } else {
            closedToday = tradeRepository.findClosedTradesAfter(startOfToday);
        }
        return closedToday.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() < 0)
                .mapToDouble(Trade::getNetProfit)
                .sum();
    }

    /**
     * 取得今日交易統計（台灣時間 00:00 起算）
     * 供每日虧損熔斷等即時查詢使用
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTodayStats() {
        LocalDateTime startOfToday = LocalDateTime.now(AppConstants.ZONE_ID).toLocalDate().atStartOfDay();
        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);
        return getStatsForDateRange(startOfToday, now);
    }

    /**
     * 取得指定時間範圍內的已平倉交易列表
     * 供每日報告的交易明細使用
     *
     * @param from 起始時間（含）
     * @param to   結束時間（不含）
     * @return 已平倉交易列表（按時間排序）
     */
    public List<Trade> getClosedTradesForRange(LocalDateTime from, LocalDateTime to) {
        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            return tradeRepository.findUserClosedTradesBetween(userId, from, to);
        }
        return tradeRepository.findClosedTradesBetween(from, to);
    }

    /**
     * 取得指定時間範圍的交易統計
     * 供每日摘要排程（昨日統計）和即時查詢使用
     *
     * @param from 起始時間（含）
     * @param to   結束時間（不含）
     */
    public Map<String, Object> getStatsForDateRange(LocalDateTime from, LocalDateTime to) {
        List<Trade> closedTrades;
        List<Trade> openTrades;

        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            closedTrades = tradeRepository.findUserClosedTradesBetween(userId, from, to);
            openTrades = tradeRepository.findByUserIdAndStatus(userId, "OPEN");
        } else {
            closedTrades = tradeRepository.findClosedTradesBetween(from, to);
            openTrades = tradeRepository.findByStatus("OPEN");
        }

        long totalCount = closedTrades.size();
        long winCount = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0)
                .count();
        long loseCount = totalCount - winCount;
        double netProfit = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit)
                .sum();
        double commission = closedTrades.stream()
                .filter(t -> t.getCommission() != null)
                .mapToDouble(Trade::getCommission)
                .sum();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trades", totalCount);
        stats.put("wins", winCount);
        stats.put("losses", loseCount);
        stats.put("netProfit", round2(netProfit));
        stats.put("commission", round2(commission));
        stats.put("openTrades", openTrades);
        return stats;
    }

    /**
     * 盈虧統計摘要
     * 多用戶模式下只統計當前用戶的交易
     */
    public Map<String, Object> getStatsSummary() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long closedCount;
        long winCount;
        double totalNetProfit;
        double grossWins;
        double grossLosses;
        double totalCommission;
        long openCount;

        if (multiUserConfig.isEnabled()) {
            String userId = getActiveUserId();
            closedCount = tradeRepository.countUserClosedTrades(userId);
            winCount = tradeRepository.countUserWinningTrades(userId);
            totalNetProfit = tradeRepository.sumUserNetProfit(userId);
            grossWins = tradeRepository.sumUserGrossWins(userId);
            grossLosses = tradeRepository.sumUserGrossLosses(userId);
            totalCommission = tradeRepository.sumUserCommission(userId);
            openCount = tradeRepository.findByUserIdAndStatus(userId, "OPEN").size();
        } else {
            closedCount = tradeRepository.countClosedTrades();
            winCount = tradeRepository.countWinningTrades();
            totalNetProfit = tradeRepository.sumNetProfit();
            grossWins = tradeRepository.sumGrossWins();
            grossLosses = tradeRepository.sumGrossLosses();
            totalCommission = tradeRepository.sumCommission();
            openCount = tradeRepository.findByStatus("OPEN").size();
        }

        // 勝率
        double winRate = closedCount > 0 ? (double) winCount / closedCount * 100 : 0;

        // Profit Factor = 總獲利 / 總虧損（絕對值）
        double profitFactor = grossLosses > 0 ? grossWins / grossLosses : 0;

        // 平均每筆盈虧
        double avgProfit = closedCount > 0 ? totalNetProfit / closedCount : 0;

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

    // ==================== WebSocket User Data Stream ====================

    /**
     * WebSocket User Data Stream 觸發的平倉記錄
     * 使用 Binance 回傳的真實數據（出場價、手續費），非估算值
     *
     * @param symbol          交易對 (e.g. BTCUSDT)
     * @param exitPrice       實際出場均價 (o.ap)
     * @param exitQuantity    實際出場數量 (o.z)
     * @param commission      實際出場手續費 (o.n), USDT
     * @param realizedProfit  幣安回報的已實現損益 (o.rp)，僅供 log 參考
     * @param orderId         Binance 訂單號 (o.i)
     * @param exitReason      出場原因: "SL_TRIGGERED" or "TP_TRIGGERED"
     * @param transactionTime 交易時間 (o.T) milliseconds
     */
    @Transactional
    public void recordCloseFromStream(String symbol, double exitPrice, double exitQuantity,
                                       double commission, double realizedProfit,
                                       String orderId, String exitReason, long transactionTime) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        if (openTradeOpt.isEmpty()) {
            log.warn("WebSocket 平倉事件但找不到 OPEN 交易: {} orderId={}", symbol, orderId);
            return;
        }

        Trade trade = openTradeOpt.get();

        // === 判斷全平 vs 部分平倉 ===
        // 有效持倉量 = remainingQuantity（部分平倉過）或 entryQuantity（從未部分平倉）
        double effectiveQty = trade.getRemainingQuantity() != null
                ? trade.getRemainingQuantity()
                : (trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0);

        // 容差 0.1%：Binance 數量可能有精度差異
        boolean isPartialClose = effectiveQty > 0 && exitQuantity < effectiveQty * 0.999;

        // 用真實數據更新
        trade.setExitPrice(exitPrice);
        trade.setExitQuantity(exitQuantity);
        trade.setExitTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(transactionTime), AppConstants.ZONE_ID));
        trade.setExitOrderId(orderId);

        // 手續費 = 入場手續費（已記錄） + 出場手續費（WebSocket 真實值）
        double entryCommission = trade.getEntryCommission() != null ? trade.getEntryCommission() : 0;

        if (isPartialClose) {
            // === 部分平倉：維持 OPEN，追蹤已平/剩餘數量 ===
            trade.setExitReason(exitReason + "_PARTIAL");
            // 不設 CLOSED，維持 OPEN

            // 累加已平倉數量
            double prevClosed = trade.getTotalClosedQuantity() != null ? trade.getTotalClosedQuantity() : 0;
            trade.setTotalClosedQuantity(prevClosed + exitQuantity);
            trade.setRemainingQuantity(effectiveQty - exitQuantity);

            // 部分平倉暫不計算最終盈虧（等全平或後續平倉再算）
            // 但記錄出場手續費的累加
            trade.setCommission(round2(entryCommission + commission));

            tradeRepository.save(trade);

            saveEvent(trade.getTradeId(), "STREAM_PARTIAL_CLOSE",
                    exitPrice, exitQuantity, orderId, exitReason, commission, realizedProfit);

            log.info("WebSocket 部分平倉: tradeId={} {} exitPrice={} exitQty={} remaining={} reason={}",
                    trade.getTradeId(), symbol, exitPrice, exitQuantity,
                    trade.getRemainingQuantity(), exitReason);
        } else {
            // === 全平倉：標 CLOSED，計算盈虧 ===
            trade.setExitReason(exitReason);
            trade.setStatus("CLOSED");
            trade.setCommission(round2(entryCommission + commission));

            // 毛利用實際出場數量（不是 entryQuantity）
            double entry = trade.getEntryPrice() != null ? trade.getEntryPrice() : 0;
            int direction = "LONG".equals(trade.getSide()) ? 1 : -1;
            double grossProfit = (exitPrice - entry) * exitQuantity * direction;
            trade.setGrossProfit(round2(grossProfit));

            // 淨利 = 毛利 - 總手續費
            trade.setNetProfit(round2(grossProfit - trade.getCommission()));

            tradeRepository.save(trade);

            saveEvent(trade.getTradeId(), "STREAM_CLOSE",
                    exitPrice, exitQuantity, orderId, exitReason, commission, realizedProfit);

            log.info("WebSocket 全平倉: tradeId={} {} exitPrice={} exitQty={} commission={} netProfit={} reason={}",
                    trade.getTradeId(), symbol, exitPrice, exitQuantity,
                    trade.getCommission(), trade.getNetProfit(), exitReason);
        }
    }

    /**
     * 寫入 WebSocket stream 平倉事件（全平/部分平倉通用）
     */
    private void saveEvent(String tradeId, String eventType,
                           double exitPrice, double exitQuantity,
                           String orderId, String exitReason,
                           double commission, double realizedProfit) {
        TradeEvent event = TradeEvent.builder()
                .tradeId(tradeId)
                .eventType(eventType)
                .binanceOrderId(orderId)
                .price(exitPrice)
                .quantity(exitQuantity)
                .success(true)
                .detail(toJson(Map.of("exit_reason", exitReason, "commission", commission, "realized_profit", realizedProfit)))
                .build();
        tradeEventRepository.save(event);
    }

    // ==================== SL/TP 保護消失偵測 ====================

    /**
     * WebSocket 偵測到 SL 或 TP 被取消/過期時記錄事件
     * 持倉仍在但失去止損/止盈保護，需要使用者注意
     *
     * @param symbol    交易對
     * @param orderType 被取消的訂單類型 (STOP_MARKET / TAKE_PROFIT_MARKET)
     * @param orderId   Binance 訂單號
     * @param reason    取消原因 (CANCELED / EXPIRED)
     */
    @Transactional
    public void recordProtectionLost(String symbol, String orderType, String orderId, String reason) {
        Optional<Trade> openTradeOpt = resolveOpenTrade(symbol);
        String tradeId = openTradeOpt.map(Trade::getTradeId).orElse("UNKNOWN");

        String eventType = "STOP_MARKET".equals(orderType) ? "SL_LOST" : "TP_LOST";

        TradeEvent event = TradeEvent.builder()
                .tradeId(tradeId)
                .eventType(eventType)
                .binanceOrderId(orderId)
                .orderType(orderType)
                .success(false)
                .detail(toJson(Map.of("reason", reason, "order_type", orderType)))
                .build();
        tradeEventRepository.save(event);

        log.warn("保護消失: tradeId={} {} {} orderId={} reason={}",
                tradeId, symbol, eventType, orderId, reason);
    }

    // ==================== 內部方法 ====================

    /**
     * 計算盈虧（毛利、手續費、淨利）
     */
    /**
     * 計算盈虧
     * @param trade 交易紀錄
     * @param realExitCommission Binance 回傳的真實出場手續費，0 則 fallback 到估算
     */
    private void calculateProfit(Trade trade, double realExitCommission) {
        if (trade.getEntryPrice() == null || trade.getExitPrice() == null) {
            return;
        }

        double entry = trade.getEntryPrice();
        double exit = trade.getExitPrice();

        // 用實際出場數量計算毛利（部分平倉後 entryQuantity ≠ 實際持倉量）
        // 優先 exitQuantity → remainingQuantity → entryQuantity (fallback)
        double qty;
        if (trade.getExitQuantity() != null && trade.getExitQuantity() > 0) {
            qty = trade.getExitQuantity();
        } else if (trade.getRemainingQuantity() != null && trade.getRemainingQuantity() > 0) {
            qty = trade.getRemainingQuantity();
        } else {
            qty = trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0;
        }

        // 方向因子：LONG → (exit - entry), SHORT → (entry - exit)
        int direction = "LONG".equals(trade.getSide()) ? 1 : -1;
        double grossProfit = (exit - entry) * qty * direction;

        // 手續費：入場 (已記錄) + 出場
        // 出場優先用真實值，fallback 到估算值（保守 taker 0.04%）
        double entryCom = trade.getEntryCommission() != null ? trade.getEntryCommission() : (entry * qty * 0.0002);
        double exitCom = realExitCommission > 0 ? round2(realExitCommission) : round2(exit * qty * 0.0004);
        double commission = entryCom + exitCom;

        double netProfit = grossProfit - commission;

        trade.setCommission(round2(commission));
        trade.setGrossProfit(round2(grossProfit));
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
     * 清理殭屍 OPEN 紀錄
     *
     * 比對 DB 中 status=OPEN 的 Trade 與幣安實際持倉，
     * 如果幣安上已無該幣種的持倉，將 DB 紀錄標記為 CANCELLED。
     *
     * @param positionChecker 查詢幣安持倉量的 function（symbol → positionAmt）
     * @return 清理結果：cleaned（清理筆數）、skipped（仍有持倉跳過）、details（明細）
     */
    @Transactional
    public Map<String, Object> cleanupStaleTrades(java.util.function.Function<String, Double> positionChecker) {
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        int cleaned = 0;
        int skipped = 0;
        List<String> details = new ArrayList<>();

        for (Trade trade : openTrades) {
            try {
                double positionAmt = positionChecker.apply(trade.getSymbol());
                if (positionAmt == 0) {
                    // 幣安無持倉 → 殭屍紀錄，標記為 CANCELLED
                    trade.setStatus("CANCELLED");
                    trade.setExitReason("STALE_CLEANUP");
                    trade.setExitTime(LocalDateTime.now(AppConstants.ZONE_ID));
                    tradeRepository.save(trade);
                    cleaned++;
                    details.add(String.format("✓ %s %s %s @ %s → CANCELLED",
                            trade.getTradeId(), trade.getSymbol(), trade.getSide(),
                            trade.getEntryPrice()));
                    log.info("清理殭屍 Trade: {} {} {}", trade.getTradeId(), trade.getSymbol(), trade.getSide());
                } else {
                    skipped++;
                    details.add(String.format("⏭ %s %s 仍有持倉 %.4f → 跳過",
                            trade.getTradeId(), trade.getSymbol(), positionAmt));
                }
            } catch (Exception e) {
                skipped++;
                details.add(String.format("⚠ %s %s 查詢失敗: %s → 跳過",
                        trade.getTradeId(), trade.getSymbol(), e.getMessage()));
                log.warn("清理時查詢持倉失敗: {} {}", trade.getSymbol(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalOpen", openTrades.size());
        result.put("cleaned", cleaned);
        result.put("skipped", skipped);
        result.put("details", details);
        return result;
    }

    /**
     * 四捨五入到小數點後 2 位
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 安全地將 Map 轉為 JSON 字串（自動處理特殊字元）
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失敗: {}", e.getMessage());
            return "{}";
        }
    }
}
