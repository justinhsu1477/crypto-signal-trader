package com.trader.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服務啟動時掃描 Binance 現有持倉和掛單，
 * 重建 MartingaleSession 和 LayerFillTracker 狀態，
 * 確保重啟後不會產生幽靈倉位。
 */
@Slf4j
@Component
public class MartingaleRecoveryTask {

    private final BinanceFuturesService binanceFuturesService;
    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;
    private final MartingaleStrategyConfig config;
    private final ObjectMapper objectMapper;

    public MartingaleRecoveryTask(BinanceFuturesService binanceFuturesService,
                                  MartingaleSessionManager sessionManager,
                                  LayerFillTracker layerFillTracker,
                                  MartingaleStrategyConfig config,
                                  ObjectMapper objectMapper) {
        this.binanceFuturesService = binanceFuturesService;
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            List<PositionSnapshot> positions = scanOpenPositions();
            if (positions.isEmpty()) {
                log.info("Martingale recovery: 無持倉，跳過復原");
                return;
            }

            int recovered = 0;
            for (PositionSnapshot pos : positions) {
                try {
                    recoverSymbol(pos);
                    recovered++;
                } catch (Exception e) {
                    log.error("Martingale recovery failed: symbol={} err={}", pos.symbol, e.getMessage(), e);
                }
            }

            log.info("Martingale recovery 完成: 掃描到 {} 個持倉，復原 {} 個 session", positions.size(), recovered);
        } catch (Exception e) {
            log.error("Martingale recovery 掃描失敗: {}", e.getMessage(), e);
        }
    }

    private void recoverSymbol(PositionSnapshot pos) {
        // 已有 active session → 跳過（不應該發生，但防禦性檢查）
        if (sessionManager.getActiveSession(pos.symbol).isPresent()) {
            log.debug("Martingale recovery: symbol={} 已有 active session，跳過", pos.symbol);
            return;
        }

        // 重建 session
        MartingaleSession session = sessionManager.startSession(
                pos.symbol, pos.side, config.getMaxLayers(), pos.entryPrice);

        log.info("Martingale recovery: 重建 session symbol={} side={} entryPrice={} qty={}",
                pos.symbol, pos.side, pos.entryPrice, pos.quantity);

        // 掃描並註冊現有 LIMIT 掛單
        registerOpenLimitOrders(pos.symbol);

        // 掃描現有 TP Algo 單
        recoverTpOrder(pos.symbol, session);
    }

    private void registerOpenLimitOrders(String symbol) {
        try {
            String response = binanceFuturesService.getOpenOrders(symbol);
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                return;
            }

            int registered = 0;
            for (JsonNode order : root) {
                String type = order.has("type") ? order.get("type").asText() : "";
                if (!"LIMIT".equals(type)) {
                    continue;
                }

                String orderId = order.has("orderId") ? String.valueOf(order.get("orderId").asLong()) : null;
                if (orderId == null) {
                    continue;
                }

                // 無法精確還原 layer 編號，用 null 表示未知層
                layerFillTracker.registerOrder(orderId, symbol, null);
                registered++;
            }

            if (registered > 0) {
                log.info("Martingale recovery: symbol={} 註冊 {} 個 LIMIT 掛單到 tracker", symbol, registered);
            }
        } catch (Exception e) {
            log.warn("Martingale recovery: 掃描 open orders 失敗 symbol={} err={}", symbol, e.getMessage());
        }
    }

    private void recoverTpOrder(String symbol, MartingaleSession session) {
        try {
            String response = binanceFuturesService.getOpenAlgoOrders(symbol);
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                return;
            }

            for (JsonNode order : root) {
                String type = order.has("type") ? order.get("type").asText() : "";
                if (!"TAKE_PROFIT_MARKET".equals(type)) {
                    continue;
                }

                String algoId = order.has("algoId") ? String.valueOf(order.get("algoId").asLong()) : null;
                if (algoId != null) {
                    session.setCurrentTpOrderId(algoId);
                    log.info("Martingale recovery: symbol={} 找到現有 TP algoId={}", symbol, algoId);
                    return;
                }
            }

            // 沒有 TP 單 → 用持倉均價掛一張
            log.warn("Martingale recovery: symbol={} 無 TP 單，將由 StopLossWatcher 或下次 fill 觸發補掛", symbol);
        } catch (Exception e) {
            log.warn("Martingale recovery: 掃描 algo orders 失敗 symbol={} err={}", symbol, e.getMessage());
        }
    }

    private List<PositionSnapshot> scanOpenPositions() throws Exception {
        String response = binanceFuturesService.getPositions();
        JsonNode root = objectMapper.readTree(response);
        if (!root.isArray()) {
            return List.of();
        }

        List<PositionSnapshot> positions = new ArrayList<>();
        for (JsonNode pos : root) {
            double positionAmt = pos.has("positionAmt") ? pos.get("positionAmt").asDouble() : 0.0;
            if (positionAmt == 0) {
                continue;
            }

            String symbol = pos.has("symbol") ? pos.get("symbol").asText() : null;
            double entryPrice = pos.has("entryPrice") ? pos.get("entryPrice").asDouble() : 0.0;
            if (symbol == null || entryPrice <= 0) {
                continue;
            }

            TradeSignal.Side side = positionAmt > 0 ? TradeSignal.Side.LONG : TradeSignal.Side.SHORT;
            double quantity = Math.abs(positionAmt);

            positions.add(new PositionSnapshot(symbol, side, quantity, entryPrice));
        }
        return positions;
    }

    private record PositionSnapshot(String symbol, TradeSignal.Side side, double quantity, double entryPrice) {}
}
