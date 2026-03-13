package com.trader.papertrade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.papertrade.config.PaperTradingConfig;
import com.trader.shared.config.AppConstants;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 模擬交易引擎 — 為 SHADOW 頻道建立虛擬 Trade 紀錄
 *
 * 不呼叫任何 Binance 交易 API，只記錄虛擬持倉並計算 PnL。
 * 未來可獨立部署為微服務，只需共享 DB + Binance 公開 API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradeService {

    public static final String PAPER_TRADE_USER_ID = "PAPER_TRADE_SYSTEM";

    private final TradeRepository tradeRepository;
    private final PaperTradingConfig config;
    private final ObjectMapper objectMapper;

    /**
     * SHADOW ENTRY 訊號 → 建立模擬交易
     */
    @Transactional
    public Trade createPaperTrade(TradeRequest request, SignalScore score) {
        if (request.getEntryPrice() == null || request.getEntryPrice() <= 0) {
            throw new IllegalArgumentException("模擬交易需要有效的入場價: entryPrice=" + request.getEntryPrice());
        }

        double entryPrice = request.getEntryPrice();
        double notional = config.getNotionalUsdt() * config.getLeverage();
        double quantity = round6(notional / entryPrice);
        double entryCommission = round2(entryPrice * quantity * 0.0002); // maker 0.02%

        // 止盈序列化
        String takeProfitsJson = null;
        if (request.getTakeProfit() != null) {
            try {
                takeProfitsJson = objectMapper.writeValueAsString(
                        Map.of("targets", List.of(request.getTakeProfit())));
            } catch (Exception e) {
                log.warn("止盈序列化失敗: {}", e.getMessage());
            }
        }

        // 建立模擬 Trade
        Trade trade = Trade.builder()
                .tradeId(UUID.randomUUID().toString())
                .userId(PAPER_TRADE_USER_ID)
                .symbol(request.getSymbol())
                .side(request.getSide())
                .entryPrice(entryPrice)
                .entryQuantity(quantity)
                .entryTime(LocalDateTime.now(AppConstants.ZONE_ID))
                .entryOrderId("PAPER")
                .stopLoss(request.getStopLoss())
                .takeProfits(takeProfitsJson)
                .leverage(config.getLeverage())
                .riskAmount(round2(config.getNotionalUsdt()))
                .entryCommission(entryCommission)
                .status("OPEN")
                .simulated(true)
                .build();

        // 寫入訊號來源
        SignalSource src = request.getSource();
        if (src != null) {
            trade.setSourcePlatform(src.getPlatform());
            trade.setSourceChannelId(src.getChannelId());
            trade.setSourceGuildId(src.getGuildId());
            trade.setSourceAuthorName(src.getAuthorName());
            trade.setSourceMessageId(src.getMessageId());
        }

        // AI 評分
        if (score != null) {
            trade.setAiConfidence(score.getConfidence());
            trade.setAiReasoning(score.getReasoning());
        }

        tradeRepository.save(trade);
        log.info("模擬交易已建立: tradeId={} {} {} entry={} qty={} SL={} TP={}",
                trade.getTradeId(), request.getSymbol(), request.getSide(),
                entryPrice, quantity, request.getStopLoss(), request.getTakeProfit());
        return trade;
    }

    /**
     * 模擬平倉 — 計算 PnL 並標為 CLOSED
     */
    @Transactional
    public Optional<Trade> closePaperTrade(String symbol, String channelId, double exitPrice, String exitReason) {
        Optional<Trade> opt = tradeRepository.findOpenSimulatedTrade(symbol, channelId);
        if (opt.isEmpty()) {
            log.debug("找不到模擬持倉: {} channelId={}", symbol, channelId);
            return Optional.empty();
        }

        Trade trade = opt.get();
        trade.setExitPrice(exitPrice);
        trade.setExitQuantity(trade.getEntryQuantity());
        trade.setExitTime(LocalDateTime.now(AppConstants.ZONE_ID));
        trade.setExitOrderId("PAPER");
        trade.setExitReason(exitReason);
        trade.setStatus("CLOSED");

        // PnL 計算（與 TradeRecordService.calculateProfit 同公式）
        calculateProfit(trade);

        tradeRepository.save(trade);
        log.info("模擬交易已平倉: tradeId={} {} exitPrice={} exitReason={} netProfit={}",
                trade.getTradeId(), symbol, exitPrice, exitReason, trade.getNetProfit());
        return Optional.of(trade);
    }

    /**
     * 更新模擬持倉的止損/止盈
     */
    @Transactional
    public Optional<Trade> movePaperStopLoss(String symbol, String channelId, Double newSl, Double newTp) {
        Optional<Trade> opt = tradeRepository.findOpenSimulatedTrade(symbol, channelId);
        if (opt.isEmpty()) {
            log.debug("找不到模擬持倉可移動止損: {} channelId={}", symbol, channelId);
            return Optional.empty();
        }

        Trade trade = opt.get();
        if (newSl != null) {
            trade.setStopLoss(newSl);
        }
        if (newTp != null) {
            try {
                trade.setTakeProfits(objectMapper.writeValueAsString(
                        Map.of("targets", List.of(newTp))));
            } catch (Exception e) {
                log.warn("止盈序列化失敗: {}", e.getMessage());
            }
        }

        tradeRepository.save(trade);
        log.info("模擬持倉止損已更新: tradeId={} {} newSL={} newTP={}",
                trade.getTradeId(), symbol, newSl, newTp);
        return Optional.of(trade);
    }

    /**
     * PnL 計算 — 複用 TradeRecordService 的公式
     * netProfit = (exitPrice - entryPrice) * qty * direction - commission
     */
    private void calculateProfit(Trade trade) {
        double entry = trade.getEntryPrice();
        double exit = trade.getExitPrice();
        double qty = trade.getEntryQuantity();
        int direction = "LONG".equals(trade.getSide()) ? 1 : -1;

        double grossProfit = (exit - entry) * qty * direction;

        // 手續費估算：入場 maker 0.02% + 出場 taker 0.04%
        double entryComm = trade.getEntryCommission() != null ? trade.getEntryCommission() : round2(entry * qty * 0.0002);
        double exitComm = round2(exit * qty * 0.0004);
        double commission = entryComm + exitComm;

        double netProfit = grossProfit - commission;

        trade.setGrossProfit(round2(grossProfit));
        trade.setCommission(round2(commission));
        trade.setNetProfit(round2(netProfit));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
