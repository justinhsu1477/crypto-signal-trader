package com.trader.controller;

import com.trader.entity.Trade;
import com.trader.entity.TradeEvent;
import com.trader.model.OrderResult;
import com.trader.model.TradeRequest;
import com.trader.model.TradeSignal;
import com.trader.service.BinanceFuturesService;
import com.trader.service.DiscordWebhookService;
import com.trader.service.MonitorHeartbeatService;
import com.trader.service.SignalDeduplicationService;
import com.trader.service.SignalParserService;
import com.trader.service.TradeRecordService;
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
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final DiscordWebhookService webhookService;
    private final MonitorHeartbeatService heartbeatService;

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
            log.debug("訊號解析失敗，非交易訊號: {}", message != null ? message.substring(0, Math.min(message.length(), 100)) : "null");
            return ResponseEntity.ok(Map.of(
                    "action", "IGNORED",
                    "reason", "非交易訊號，無法解析"));
        }

        TradeSignal signal = signalOpt.get();

        // 處理取消掛單
        if (signal.getSignalType() == TradeSignal.SignalType.CANCEL) {
            if (deduplicationService.isCancelDuplicate(signal.getSymbol())) {
                webhookService.sendNotification(
                        "⏭️ 重複取消跳過",
                        signal.getSymbol() + " — 30秒內已收到相同取消訊號",
                        DiscordWebhookService.COLOR_YELLOW);
                return ResponseEntity.ok(Map.of("action", "CANCEL", "status", "SKIPPED", "reason", "重複取消訊號"));
            }
            String result = binanceFuturesService.cancelAllOrders(signal.getSymbol());
            try {
                tradeRecordService.recordCancel(signal.getSymbol());
            } catch (Exception e) {
                log.error("取消紀錄寫入失敗: {}", e.getMessage());
            }
            webhookService.sendNotification(
                    "🚫 CANCEL 取消掛單",
                    signal.getSymbol() + " — 已取消所有掛單",
                    DiscordWebhookService.COLOR_BLUE);
            return ResponseEntity.ok(Map.of("action", "CANCEL", "symbol", signal.getSymbol(), "result", result));
        }

        // 處理資訊通知
        if (signal.getSignalType() == TradeSignal.SignalType.INFO) {
            return ResponseEntity.ok(Map.of("action", "INFO", "message", "已記錄，不執行下單"));
        }

        // 白名單檢查
        if (!riskConfig.isSymbolAllowed(signal.getSymbol())) {
            webhookService.sendNotification(
                    "⚠️ 風控攔截 — 交易對不在白名單",
                    "收到: " + signal.getSymbol() + "\n允許: " + riskConfig.getAllowedSymbols(),
                    DiscordWebhookService.COLOR_YELLOW);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "交易對不在白名單",
                    "allowed", riskConfig.getAllowedSymbols().toString(),
                    "received", signal.getSymbol()));
        }

        // 路由到對應操作
        if (signal.getSignalType() == TradeSignal.SignalType.CLOSE) {
            List<OrderResult> results = binanceFuturesService.executeClose(signal);
            boolean allSuccess = results.stream().allMatch(OrderResult::isSuccess);
            webhookService.sendNotification(
                    allSuccess ? "💰 CLOSE 平倉成功" : "❌ CLOSE 平倉失敗",
                    formatCloseResults(signal.getSymbol(), results),
                    allSuccess ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
            return ResponseEntity.ok(Map.of("action", "CLOSE", "results", results));
        }

        if (signal.getSignalType() == TradeSignal.SignalType.MOVE_SL) {
            List<OrderResult> results = binanceFuturesService.executeMoveSL(signal);
            boolean allSuccess = results.stream().allMatch(OrderResult::isSuccess);
            webhookService.sendNotification(
                    allSuccess ? "🔄 TP/SL 修改成功" : "❌ TP/SL 修改失敗",
                    formatMoveSLResults(signal, results),
                    allSuccess ? DiscordWebhookService.COLOR_BLUE : DiscordWebhookService.COLOR_RED);
            return ResponseEntity.ok(Map.of("action", "MOVE_SL", "results", results));
        }

        // ENTRY: 止損是必須的，不再自動補充預設值
        if (signal.getStopLoss() == 0) {
            webhookService.sendNotification(
                    "⚠️ 風控攔截 — 缺少止損",
                    signal.getSymbol() + " " + signal.getSide() + "\nENTRY 訊號必須包含 stop_loss",
                    DiscordWebhookService.COLOR_YELLOW);
            return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 訊號必須包含 stop_loss"));
        }

        List<OrderResult> results = binanceFuturesService.executeSignal(signal);
        boolean entrySuccess = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
        webhookService.sendNotification(
                entrySuccess ? "✅ ENTRY 入場成功" : "❌ ENTRY 入場失敗",
                formatEntryResults(signal, results),
                entrySuccess ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
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
        if (symbol == null || !riskConfig.isSymbolAllowed(symbol)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "交易對不在白名單",
                    "allowed", riskConfig.getAllowedSymbols().toString(),
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
                boolean entryOk = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
                webhookService.sendNotification(
                        entryOk ? "✅ ENTRY 入場成功 (API)" : "❌ ENTRY 入場失敗 (API)",
                        formatEntryResults(signal, results),
                        entryOk ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
                return ResponseEntity.ok(Map.of("action", "ENTRY", "results", results));
            }

            case "CLOSE": {
                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.CLOSE)
                        .closeRatio(request.getCloseRatio())
                        .build();

                List<OrderResult> results = binanceFuturesService.executeClose(signal);
                boolean closeOk = results.stream().allMatch(OrderResult::isSuccess);
                webhookService.sendNotification(
                        closeOk ? "💰 CLOSE 平倉成功 (API)" : "❌ CLOSE 平倉失敗 (API)",
                        formatCloseResults(symbol, results),
                        closeOk ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
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
                boolean moveOk = results.stream().allMatch(OrderResult::isSuccess);
                webhookService.sendNotification(
                        moveOk ? "🔄 TP/SL 修改成功 (API)" : "❌ TP/SL 修改失敗 (API)",
                        formatMoveSLResults(signal, results),
                        moveOk ? DiscordWebhookService.COLOR_BLUE : DiscordWebhookService.COLOR_RED);
                return ResponseEntity.ok(Map.of("action", "MOVE_SL", "results", results));
            }

            case "CANCEL": {
                if (deduplicationService.isCancelDuplicate(symbol)) {
                    webhookService.sendNotification(
                            "⏭️ 重複取消跳過 (API)",
                            symbol + " — 30秒內已收到相同取消訊號",
                            DiscordWebhookService.COLOR_YELLOW);
                    return ResponseEntity.ok(Map.of("action", "CANCEL", "status", "SKIPPED", "reason", "重複取消訊號"));
                }
                String cancelResult = binanceFuturesService.cancelAllOrders(symbol);
                try {
                    tradeRecordService.recordCancel(symbol);
                } catch (Exception e) {
                    log.error("取消紀錄寫入失敗: {}", e.getMessage());
                }
                webhookService.sendNotification(
                        "🚫 CANCEL 取消掛單 (API)",
                        symbol + " — 已取消所有掛單",
                        DiscordWebhookService.COLOR_BLUE);
                return ResponseEntity.ok(Map.of("action", "CANCEL", "symbol", symbol, "result", cancelResult));
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

    // ==================== Monitor 心跳 ====================

    /**
     * Discord Monitor 心跳端點
     * POST /api/heartbeat
     * Body: { "status": "connected" }
     *
     * Python monitor 每 30 秒呼叫一次，Java 端超過 90 秒沒收到就告警
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody(required = false) Map<String, String> body) {
        String status = (body != null && body.containsKey("status")) ? body.get("status") : "unknown";
        return ResponseEntity.ok(heartbeatService.receiveHeartbeat(status));
    }

    /**
     * 查詢 Monitor 連線狀態
     * GET /api/monitor-status
     */
    @GetMapping("/monitor-status")
    public ResponseEntity<Map<String, Object>> getMonitorStatus() {
        return ResponseEntity.ok(heartbeatService.getStatus());
    }

    // ==================== Webhook 通知格式化 ====================

    private String formatEntryResults(TradeSignal signal, List<OrderResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(signal.getSymbol()).append(" ").append(signal.getSide()).append("\n");
        sb.append("入場: ").append(signal.getEntryPriceLow());
        if (signal.getEntryPriceHigh() != signal.getEntryPriceLow()) {
            sb.append("~").append(signal.getEntryPriceHigh());
        }
        sb.append("\n");
        sb.append("止損: ").append(signal.getStopLoss());
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            sb.append(" | 止盈: ").append(signal.getTakeProfits().get(0));
        }
        sb.append("\n");

        for (OrderResult r : results) {
            if (r.isSuccess() && r.getOrderId() != null) {
                sb.append("✓ ").append(r.getType() != null ? r.getType() : "ORDER")
                        .append(" qty=").append(r.getQuantity())
                        .append(" price=").append(r.getPrice()).append("\n");
                // 風控摘要（只有入場單有）
                if (r.getRiskSummary() != null) {
                    sb.append(r.getRiskSummary()).append("\n");
                }
            } else if (!r.isSuccess()) {
                sb.append("✗ ").append(r.getErrorMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatCloseResults(String symbol, List<OrderResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append("\n");
        for (OrderResult r : results) {
            if (r.isSuccess()) {
                sb.append("✓ 平倉 qty=").append(r.getQuantity())
                        .append(" price=").append(r.getPrice()).append("\n");
            } else {
                sb.append("✗ ").append(r.getErrorMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatMoveSLResults(TradeSignal signal, List<OrderResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(signal.getSymbol()).append("\n");
        if (signal.getNewStopLoss() != 0) {
            sb.append("新止損: ").append(signal.getNewStopLoss()).append("\n");
        }
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            sb.append("新止盈: ").append(signal.getTakeProfits().get(0)).append("\n");
        }
        for (OrderResult r : results) {
            if (r.isSuccess()) {
                sb.append("✓ ").append(r.getType() != null ? r.getType() : "ORDER").append(" OK\n");
            } else {
                sb.append("✗ ").append(r.getErrorMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 交易紀錄與統計端點 ====================

    /**
     * 查詢所有交易紀錄（可選 status 篩選）
     * GET /api/trades
     * GET /api/trades?status=OPEN
     * GET /api/trades?status=CLOSED
     */
    @GetMapping("/trades")
    public ResponseEntity<List<Trade>> getTrades(@RequestParam(required = false) String status) {
        if (status != null) {
            return ResponseEntity.ok(tradeRecordService.findByStatus(status.toUpperCase()));
        }
        return ResponseEntity.ok(tradeRecordService.findAll());
    }

    /**
     * 查詢單筆交易詳情（含 events）
     * GET /api/trades/{tradeId}
     */
    @GetMapping("/trades/{tradeId}")
    public ResponseEntity<?> getTradeDetail(@PathVariable String tradeId) {
        Optional<Trade> tradeOpt = tradeRecordService.findById(tradeId);
        if (tradeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(tradeOpt.get());
    }

    /**
     * 查詢單筆交易的事件日誌
     * GET /api/trades/{tradeId}/events
     */
    @GetMapping("/trades/{tradeId}/events")
    public ResponseEntity<List<TradeEvent>> getTradeEvents(@PathVariable String tradeId) {
        return ResponseEntity.ok(tradeRecordService.findEvents(tradeId));
    }

    /**
     * 盈虧統計摘要
     * GET /api/stats/summary
     *
     * 回傳: closedTrades, winningTrades, winRate, totalNetProfit,
     *       grossWins, grossLosses, profitFactor, avgProfitPerTrade,
     *       totalCommission, openPositions
     */
    @GetMapping("/stats/summary")
    public ResponseEntity<Map<String, Object>> getStatsSummary() {
        return ResponseEntity.ok(tradeRecordService.getStatsSummary());
    }
}
