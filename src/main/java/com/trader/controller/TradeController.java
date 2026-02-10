package com.trader.controller;

import com.trader.model.OrderResult;
import com.trader.model.TradeRequest;
import com.trader.model.TradeSignal;
import com.trader.service.BinanceFuturesService;
import com.trader.service.SignalParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.trader.config.RiskConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 測試用 API 控制器

 * 提供 REST 端點方便你用 Postman 或 curl 測試各功能
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TradeController {

    private final BinanceFuturesService binanceFuturesService;
    private final SignalParserService signalParserService;
    private final RiskConfig riskConfig;

    /**
     * 查詢帳戶餘額
     * GET /api/balance
     */
    @GetMapping("/balance")
    public ResponseEntity<String> getBalance() {
        return ResponseEntity.ok(binanceFuturesService.getAccountBalance());
    }

    /**
     * 查詢當前持倉
     * GET /api/positions
     */
    @GetMapping("/positions")
    public ResponseEntity<String> getPositions() {
        return ResponseEntity.ok(binanceFuturesService.getPositions());
    }

    /**
     * 查詢交易對資訊
     * GET /api/exchange-info
     */
    @GetMapping("/exchange-info")
    public ResponseEntity<String> getExchangeInfo() {
        return ResponseEntity.ok(binanceFuturesService.getExchangeInfo());
    }

    /**
     * 查詢未成交訂單
     * GET /api/open-orders?symbol=BTCUSDT
     */
    @GetMapping("/open-orders")
    public ResponseEntity<String> getOpenOrders(@RequestParam String symbol) {
        return ResponseEntity.ok(binanceFuturesService.getOpenOrders(symbol));
    }

    /**
     * 測試解析訊號 (不會下單)
     * POST /api/parse-signal
     * Body: { "message": "陈哥合约交易策略【限价】\nBTC，70800-72000附近，做空\n止损预计: 72800\n止盈预计: 68400/66700" }
     */
    @PostMapping("/parse-signal")
    public ResponseEntity<?> parseSignal(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        Optional<TradeSignal> signal = signalParserService.parse(message);

        if (signal.isPresent()) {
            return ResponseEntity.ok(signal.get());
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "無法解析訊號", "message", message));
        }
    }

    /**
     * 接收訊號並執行下單 (完整流程)
     * POST /api/execute-signal
     * Body: { "message": "..." }
     */
    @PostMapping("/execute-signal")
    public ResponseEntity<?> executeSignal(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        Optional<TradeSignal> signalOpt = signalParserService.parse(message);

        if (signalOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "無法解析訊號"));
        }

        TradeSignal signal = signalOpt.get();

        // 處理取消掛單
        if (signal.getSignalType() == TradeSignal.SignalType.CANCEL) {
            String result = binanceFuturesService.cancelAllOrders(signal.getSymbol());
            return ResponseEntity.ok(Map.of("action", "CANCEL", "symbol", signal.getSymbol(), "result", result));
        }

        // 處理資訊通知
        if (signal.getSignalType() == TradeSignal.SignalType.INFO) {
            return ResponseEntity.ok(Map.of("action", "INFO", "message", "已記錄，不執行下單"));
        }

        // 白名單檢查
        if (!signal.getSymbol().equals(riskConfig.getAllowedSymbol())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "僅允許 " + riskConfig.getAllowedSymbol(),
                    "received", signal.getSymbol()));
        }

        // 路由到對應操作
        if (signal.getSignalType() == TradeSignal.SignalType.CLOSE) {
            List<OrderResult> results = binanceFuturesService.executeClose(signal);
            return ResponseEntity.ok(Map.of("action", "CLOSE", "results", results));
        }

        if (signal.getSignalType() == TradeSignal.SignalType.MOVE_SL) {
            List<OrderResult> results = binanceFuturesService.executeMoveSL(signal);
            return ResponseEntity.ok(Map.of("action", "MOVE_SL", "results", results));
        }

        // ENTRY: 止損是必須的，不再自動補充預設值
        if (signal.getStopLoss() == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 訊號必須包含 stop_loss"));
        }

        List<OrderResult> results = binanceFuturesService.executeSignal(signal);
        return ResponseEntity.ok(Map.of("action", "ENTRY", "results", results));
    }

    /**
     * 接收結構化 JSON 並執行交易（給 Python AI 用）
     * POST /api/execute-trade
     *
     * ENTRY: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":94000}
     * CLOSE: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5}
     * MOVE_SL: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":95500}
     */
    @PostMapping("/execute-trade")
    public ResponseEntity<?> executeTrade(@RequestBody TradeRequest request) {
        log.info("收到結構化交易請求: action={} symbol={}", request.getAction(), request.getSymbol());

        // 白名單檢查
        String symbol = request.getSymbol();
        if (symbol == null || !symbol.equals(riskConfig.getAllowedSymbol())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "僅允許 " + riskConfig.getAllowedSymbol(),
                    "received", symbol != null ? symbol : "null"));
        }

        String action = request.getAction();
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "action 不可為空"));
        }

        switch (action.toUpperCase()) {
            case "ENTRY": {
                // 驗證必要欄位
                if (request.getSide() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 需要 side (LONG/SHORT)"));
                }
                if (request.getEntryPrice() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 需要 entry_price"));
                }
                if (request.getStopLoss() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 必須包含 stop_loss"));
                }

                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .side(TradeSignal.Side.valueOf(request.getSide().toUpperCase()))
                        .entryPriceLow(request.getEntryPrice())
                        .entryPriceHigh(request.getEntryPrice())
                        .stopLoss(request.getStopLoss())
                        .signalType(TradeSignal.SignalType.ENTRY)
                        .build();

                // 設定 TP（如果有的話）
                if (request.getTakeProfit() != null) {
                    signal.setTakeProfits(List.of(request.getTakeProfit()));
                }

                List<OrderResult> results = binanceFuturesService.executeSignal(signal);
                return ResponseEntity.ok(Map.of("action", "ENTRY", "results", results));
            }

            case "CLOSE": {
                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.CLOSE)
                        .closeRatio(request.getCloseRatio())
                        .build();

                List<OrderResult> results = binanceFuturesService.executeClose(signal);
                return ResponseEntity.ok(Map.of("action", "CLOSE", "results", results));
            }

            case "MOVE_SL": {
                if (request.getNewStopLoss() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "MOVE_SL 需要 new_stop_loss"));
                }

                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.MOVE_SL)
                        .newStopLoss(request.getNewStopLoss())
                        .build();

                List<OrderResult> results = binanceFuturesService.executeMoveSL(signal);
                return ResponseEntity.ok(Map.of("action", "MOVE_SL", "results", results));
            }

            default:
                return ResponseEntity.badRequest().body(Map.of("error", "不支援的 action: " + action));
        }
    }

    /**
     * 手動設定槓桿
     * POST /api/leverage
     * Body: { "symbol": "BTCUSDT", "leverage": 10 }
     */
    @PostMapping("/leverage")
    public ResponseEntity<String> setLeverage(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        int leverage = (int) body.get("leverage");
        return ResponseEntity.ok(binanceFuturesService.setLeverage(symbol, leverage));
    }

    /**
     * 取消所有訂單
     * DELETE /api/orders?symbol=BTCUSDT
     */
    @DeleteMapping("/orders")
    public ResponseEntity<String> cancelAllOrders(@RequestParam String symbol) {
        return ResponseEntity.ok(binanceFuturesService.cancelAllOrders(symbol));
    }
}
