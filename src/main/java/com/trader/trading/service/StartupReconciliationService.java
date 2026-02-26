package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import com.trader.notification.service.DiscordWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 應用啟動時自動對帳服務
 *
 * 解決場景：
 * 1. PENDING_CLOSE — MARKET 平倉單已送出但 exitPrice=0，應用重啟後 WebSocket 無法補回
 * 2. 殭屍 OPEN Trade — 應用離線期間 SL/TP 在 Binance 端觸發，但 DB 仍標 OPEN
 *
 * 策略：
 * - PENDING_CLOSE → 查詢 Binance 當前持倉，若已無持倉則標為 CLOSED（用 markPrice 估算 exitPrice）
 * - OPEN → 與 Binance getCurrentPositionAmount 比對，若無持倉則標為 CANCELLED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupReconciliationService {

    private final TradeRepository tradeRepository;
    private final BinanceFuturesService binanceFuturesService;
    private final DiscordWebhookService discordWebhookService;

    /**
     * 應用完全啟動後執行對帳（ApplicationReadyEvent 確保所有 Bean 已初始化）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("========== 啟動對帳開始 ==========");
        try {
            List<String> report = new ArrayList<>();

            int pendingFixed = reconcilePendingCloseTrades(report);
            int zombieCleaned = reconcileZombieOpenTrades(report);

            log.info("啟動對帳完成: PENDING_CLOSE 修復={}, 殭屍清理={}", pendingFixed, zombieCleaned);

            if (pendingFixed > 0 || zombieCleaned > 0) {
                String details = String.join("\n", report);
                discordWebhookService.sendNotification(
                        "🔄 啟動對帳完成",
                        String.format("PENDING_CLOSE 修復: %d 筆\n殭屍 Trade 清理: %d 筆\n\n%s",
                                pendingFixed, zombieCleaned, details),
                        DiscordWebhookService.COLOR_BLUE);
            } else {
                log.info("啟動對帳: 無需修復，所有 Trade 狀態一致");
            }
        } catch (Exception e) {
            log.error("啟動對帳失敗: {}", e.getMessage(), e);
            discordWebhookService.sendNotification(
                    "⚠️ 啟動對帳失敗",
                    "原因: " + e.getMessage() + "\n請手動檢查 OPEN/PENDING_CLOSE 交易",
                    DiscordWebhookService.COLOR_YELLOW);
        }
        log.info("========== 啟動對帳結束 ==========");
    }

    /**
     * 修復 PENDING_CLOSE 交易
     * 若 Binance 已無持倉 → 用 markPrice 估算 exitPrice 並標為 CLOSED
     * 若 Binance 仍有持倉 → 保持 PENDING_CLOSE（WebSocket 重連後會收到事件）
     */
    @Transactional
    int reconcilePendingCloseTrades(List<String> report) {
        List<Trade> pendingTrades = tradeRepository.findByStatus("PENDING_CLOSE");
        if (pendingTrades.isEmpty()) return 0;

        log.info("發現 {} 筆 PENDING_CLOSE 交易待修復", pendingTrades.size());
        int fixed = 0;

        for (Trade trade : pendingTrades) {
            try {
                double positionAmt = binanceFuturesService.getCurrentPositionAmount(trade.getSymbol());

                if (positionAmt == 0) {
                    // Binance 已無持倉 → 用 markPrice 作為估算 exitPrice
                    double estimatedExitPrice = binanceFuturesService.getMarkPrice(trade.getSymbol());

                    trade.setStatus("CLOSED");
                    if (trade.getExitPrice() == null || trade.getExitPrice() == 0) {
                        trade.setExitPrice(estimatedExitPrice);
                    }
                    trade.setExitReason(trade.getExitReason() != null
                            ? trade.getExitReason() + "_STARTUP_RECONCILED"
                            : "STARTUP_RECONCILED");
                    trade.setUpdatedAt(LocalDateTime.now(AppConstants.ZONE_ID));

                    // 簡易 PnL 估算
                    calculateEstimatedProfit(trade);

                    tradeRepository.save(trade);
                    fixed++;

                    String detail = String.format("✅ %s %s PENDING_CLOSE → CLOSED (估算 exitPrice=%.2f)",
                            trade.getTradeId(), trade.getSymbol(), estimatedExitPrice);
                    report.add(detail);
                    log.info(detail);
                } else {
                    // 仍有持倉 → WebSocket 重連後應會收到成交事件
                    String detail = String.format("⏳ %s %s PENDING_CLOSE 仍有持倉 %.4f → 等待 WebSocket",
                            trade.getTradeId(), trade.getSymbol(), positionAmt);
                    report.add(detail);
                    log.info(detail);
                }
            } catch (Exception e) {
                String detail = String.format("⚠️ %s %s 查詢失敗: %s",
                        trade.getTradeId(), trade.getSymbol(), e.getMessage());
                report.add(detail);
                log.warn(detail);
            }
        }

        return fixed;
    }

    /**
     * 清理殭屍 OPEN 交易
     * 若 Binance 已無持倉 且 無未成交掛單 → 標為 CANCELLED (STALE_CLEANUP_STARTUP)
     *
     * 注意：若 Binance 無持倉但仍有 LIMIT 掛單（入場單尚未成交），
     * 不應標為 CANCELLED，需等待掛單成交或過期。
     */
    @Transactional
    int reconcileZombieOpenTrades(List<String> report) {
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        if (openTrades.isEmpty()) return 0;

        log.info("檢查 {} 筆 OPEN 交易是否為殭屍紀錄", openTrades.size());
        int cleaned = 0;

        for (Trade trade : openTrades) {
            try {
                double positionAmt = binanceFuturesService.getCurrentPositionAmount(trade.getSymbol());

                if (positionAmt == 0) {
                    // 無持倉 → 再檢查是否有未成交的入場掛單（LIMIT 單可能尚未成交）
                    boolean hasPendingOrders = false;
                    try {
                        hasPendingOrders = binanceFuturesService.hasOpenEntryOrders(trade.getSymbol());
                    } catch (Exception orderEx) {
                        // 查詢掛單失敗 → 保守策略：不標 CANCELLED，避免誤殺
                        String detail = String.format("⏳ %s %s 無持倉但查詢掛單失敗: %s → 保守跳過",
                                trade.getTradeId(), trade.getSymbol(), orderEx.getMessage());
                        report.add(detail);
                        log.warn(detail);
                        continue;
                    }

                    if (hasPendingOrders) {
                        // 有未成交掛單 → 不清理，等待掛單成交或過期
                        String detail = String.format("⏳ %s %s %s 無持倉但有未成交掛單 → 保留 OPEN",
                                trade.getTradeId(), trade.getSymbol(), trade.getSide());
                        report.add(detail);
                        log.info(detail);
                    } else {
                        // 無持倉 + 無掛單 → 確認為殭屍紀錄
                        trade.setStatus("CANCELLED");
                        trade.setExitReason("STALE_CLEANUP_STARTUP");
                        trade.setExitTime(LocalDateTime.now(AppConstants.ZONE_ID));
                        trade.setUpdatedAt(LocalDateTime.now(AppConstants.ZONE_ID));
                        tradeRepository.save(trade);
                        cleaned++;

                        String detail = String.format("🧹 %s %s %s OPEN → CANCELLED (Binance 無持倉且無掛單)",
                                trade.getTradeId(), trade.getSymbol(), trade.getSide());
                        report.add(detail);
                        log.info(detail);
                    }
                }
                // 仍有持倉的 OPEN trade 不做任何處理（正常狀態）
            } catch (Exception e) {
                String detail = String.format("⚠️ %s %s 查詢失敗: %s → 跳過",
                        trade.getTradeId(), trade.getSymbol(), e.getMessage());
                report.add(detail);
                log.warn(detail);
            }
        }

        return cleaned;
    }

    /**
     * 簡易 PnL 估算（啟動對帳用，非精確）
     * 無法取得真實手續費，用 0.04% 保守估算
     */
    private void calculateEstimatedProfit(Trade trade) {
        try {
            double entryPrice = trade.getEntryPrice() != null ? trade.getEntryPrice() : 0;
            double exitPrice = trade.getExitPrice() != null ? trade.getExitPrice() : 0;
            double qty = trade.getEntryQuantity() != null ? trade.getEntryQuantity() : 0;

            if (entryPrice <= 0 || exitPrice <= 0 || qty <= 0) return;

            double grossProfit;
            if ("LONG".equals(trade.getSide())) {
                grossProfit = (exitPrice - entryPrice) * qty;
            } else {
                grossProfit = (entryPrice - exitPrice) * qty;
            }

            // 保守估算手續費：進場 + 出場各 0.04%
            double estimatedCommission = (entryPrice * qty * 0.0004) + (exitPrice * qty * 0.0004);
            double netProfit = grossProfit - estimatedCommission;

            trade.setGrossProfit(Math.round(grossProfit * 100.0) / 100.0);
            trade.setCommission(Math.round(estimatedCommission * 100.0) / 100.0);
            trade.setNetProfit(Math.round(netProfit * 100.0) / 100.0);

            log.info("啟動對帳 PnL 估算: {} {} gross={} commission={} net={}",
                    trade.getTradeId(), trade.getSymbol(), trade.getGrossProfit(),
                    trade.getCommission(), trade.getNetProfit());
        } catch (Exception e) {
            log.warn("啟動對帳 PnL 估算失敗: {} {}", trade.getTradeId(), e.getMessage());
        }
    }
}
