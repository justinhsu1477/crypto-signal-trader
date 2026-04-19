package com.trader.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.entity.Trade;
import com.trader.trading.model.PositionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class PositionService {

    private final BinanceFuturesService binanceFuturesService;
    private final TradeRecordService tradeRecordService;
    private final ObjectMapper objectMapper;

    public PositionService(BinanceFuturesService binanceFuturesService,
                           TradeRecordService tradeRecordService,
                           ObjectMapper objectMapper) {
        this.binanceFuturesService = binanceFuturesService;
        this.tradeRecordService = tradeRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * Primary source: Binance positionRisk API.
     * Fallback: local DB open trade.
     */
    public Optional<PositionInfo> getPosition(String symbol) {
        Optional<PositionInfo> exchange = getPositionFromExchange(symbol);
        if (exchange.isPresent()) {
            return exchange;
        }
        return getPositionFromLocal(symbol);
    }

    private Optional<PositionInfo> getPositionFromExchange(String symbol) {
        try {
            String response = binanceFuturesService.getPositions();
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                return Optional.empty();
            }

            for (JsonNode pos : root) {
                if (!symbol.equals(pos.get("symbol").asText())) {
                    continue;
                }

                double positionAmt = pos.get("positionAmt").asDouble();
                if (positionAmt == 0) {
                    return Optional.empty();
                }

                double entryPrice = pos.get("entryPrice").asDouble();
                double unrealizedPnl = pos.has("unRealizedProfit")
                        ? pos.get("unRealizedProfit").asDouble() : 0.0;

                TradeSignal.Side side = positionAmt > 0 ? TradeSignal.Side.LONG : TradeSignal.Side.SHORT;
                double quantity = Math.abs(positionAmt);

                return Optional.of(new PositionInfo(symbol, side, quantity, entryPrice, unrealizedPnl));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.warn("Position fetch failed from exchange: symbol={} err={}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PositionInfo> getPositionFromLocal(String symbol) {
        try {
            Optional<Trade> tradeOpt = tradeRecordService.findOpenTrade(symbol);
            if (tradeOpt.isEmpty()) {
                return Optional.empty();
            }

            Trade trade = tradeOpt.get();
            Double entryPrice = trade.getEntryPrice();
            Double qty = trade.getRemainingQuantity() != null ? trade.getRemainingQuantity() : trade.getEntryQuantity();
            if (entryPrice == null || entryPrice <= 0 || qty == null || qty <= 0) {
                return Optional.empty();
            }

            TradeSignal.Side side = TradeSignal.Side.valueOf(trade.getSide());
            return Optional.of(new PositionInfo(symbol, side, qty, entryPrice, 0.0));
        } catch (Exception e) {
            log.warn("Position fetch failed from local: symbol={} err={}", symbol, e.getMessage());
            return Optional.empty();
        }
    }
}
