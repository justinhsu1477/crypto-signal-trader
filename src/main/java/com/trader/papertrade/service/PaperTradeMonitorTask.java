package com.trader.papertrade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模擬交易 TP/SL 定時監控
 *
 * 每 90 秒檢查所有 OPEN 模擬持倉是否觸及止盈/止損，
 * 觸及時自動模擬平倉並計算 PnL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperTradeMonitorTask {

    private final TradeRepository tradeRepository;
    private final PaperTradeService paperTradeService;
    private final BinancePriceClient binancePriceClient;
    private final DiscordWebhookService discordWebhookService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRateString = "${paper-trading.monitor-interval-ms:90000}", initialDelay = 60_000)
    public void monitorOpenPaperTrades() {
        List<Trade> openTrades = tradeRepository.findAllOpenSimulatedTrades();
        if (openTrades.isEmpty()) {
            return;
        }

        // 收集所有需要查價的幣種
        Set<String> symbols = openTrades.stream()
                .map(Trade::getSymbol)
                .collect(Collectors.toSet());

        Map<String, Double> prices;
        try {
            prices = binancePriceClient.getAllMarkPrices();
        } catch (Exception e) {
            log.warn("模擬交易監控：批次查價失敗，本輪跳過: {}", e.getMessage());
            return;
        }

        int closedCount = 0;
        for (Trade trade : openTrades) {
            Double currentPrice = prices.get(trade.getSymbol());
            if (currentPrice == null) {
                continue;
            }

            String exitReason = checkTpSlHit(trade, currentPrice);
            if (exitReason != null) {
                double exitPrice = "STOP_LOSS".equals(exitReason) ? trade.getStopLoss() : getFirstTp(trade);
                try {
                    paperTradeService.closePaperTrade(trade.getSymbol(), trade.getSourceChannelId(), exitPrice, exitReason);
                    closedCount++;
                    notifyAdmin(trade, exitPrice, exitReason);
                } catch (Exception e) {
                    log.warn("模擬交易自動平倉失敗: tradeId={} {}", trade.getTradeId(), e.getMessage());
                }
            }
        }

        if (closedCount > 0) {
            log.info("模擬交易監控完成: 檢查 {} 筆持倉，自動平倉 {} 筆", openTrades.size(), closedCount);
        }
    }

    /**
     * 檢查是否觸及 TP/SL
     * @return exitReason ("STOP_LOSS" or "TAKE_PROFIT") or null
     */
    private String checkTpSlHit(Trade trade, double currentPrice) {
        boolean isLong = "LONG".equals(trade.getSide());

        // 檢查止損
        if (trade.getStopLoss() != null) {
            if (isLong && currentPrice <= trade.getStopLoss()) {
                return "STOP_LOSS";
            }
            if (!isLong && currentPrice >= trade.getStopLoss()) {
                return "STOP_LOSS";
            }
        }

        // 檢查止盈（取第一個 TP 目標）
        double firstTp = getFirstTp(trade);
        if (firstTp > 0) {
            if (isLong && currentPrice >= firstTp) {
                return "TAKE_PROFIT";
            }
            if (!isLong && currentPrice <= firstTp) {
                return "TAKE_PROFIT";
            }
        }

        return null;
    }

    /**
     * 從 JSON 取得第一個止盈目標
     */
    private double getFirstTp(Trade trade) {
        if (trade.getTakeProfits() == null) return 0;
        try {
            JsonNode root = objectMapper.readTree(trade.getTakeProfits());
            JsonNode targets = root.get("targets");
            if (targets != null && targets.isArray() && !targets.isEmpty()) {
                return targets.get(0).asDouble();
            }
        } catch (Exception e) {
            log.debug("解析止盈 JSON 失敗: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 通知 Admin 模擬交易自動平倉
     */
    private void notifyAdmin(Trade trade, double exitPrice, String exitReason) {
        double pnl = 0;
        if (trade.getEntryPrice() != null) {
            int dir = "LONG".equals(trade.getSide()) ? 1 : -1;
            pnl = (exitPrice - trade.getEntryPrice()) * trade.getEntryQuantity() * dir;
        }
        String emoji = pnl >= 0 ? "📈" : "📉";
        String title = String.format("%s 模擬交易自動平倉 [%s]", emoji, exitReason);
        String detail = String.format("%s %s | 入場 %.2f → 出場 %.2f | 來源: %s",
                trade.getSymbol(), trade.getSide(),
                trade.getEntryPrice(), exitPrice,
                trade.getSourceAuthorName() != null ? trade.getSourceAuthorName() : "未知");
        discordWebhookService.sendNotificationToAdmins(title, detail, pnl >= 0 ? 0x00FF00 : 0xFF0000);
    }
}
