package com.trader.trading.controller;

import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BroadcastTradeService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.service.MonitorHeartbeatService;
import com.trader.trading.service.SignalDeduplicationService;
import com.trader.trading.service.SignalParserService;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.SignalRecordService;
import com.trader.trading.service.TradeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.trader.shared.config.RiskConfig;

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
    private final BroadcastTradeService broadcastTradeService;
    private final SignalParserService signalParserService;
    private final RiskConfig riskConfig;
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final DiscordWebhookService webhookService;
    private final MonitorHeartbeatService heartbeatService;
    private final BinanceUserDataStreamService userDataStreamService;
    private final SignalRecordService signalRecordService;

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
    public ResponseEntity<?> executeSignal(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        Optional<TradeSignal> signalOpt = signalParserService.parse(message);

        if (signalOpt.isEmpty()) {
            log.debug("訊號解析失敗，非交易訊號: {}", message != null ? message.substring(0, Math.min(message.length(), 100)) : "null");
            // 記錄解析失敗的訊號（用最小 TradeSignal）
            signalRecordService.recordSignal(
                    TradeSignal.builder().rawMessage(message).build(),
                    "IGNORED", "parse-failed", null);
            return ResponseEntity.ok(Map.of(
                    "action", "IGNORED",
                    "reason", "非交易訊號，無法解析"));
        }

        TradeSignal signal = signalOpt.get();

        // 設定訊號來源（如果有的話）
        SignalSource source = extractSource(body);
        if (source != null) {
            signal.setSource(source);
        }

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
            signalRecordService.recordSignal(signal, "EXECUTED", null, null);
            return ResponseEntity.ok(Map.of("action", "CANCEL", "symbol", signal.getSymbol(), "result", result));
        }

        // 處理資訊通知
        if (signal.getSignalType() == TradeSignal.SignalType.INFO) {
            signalRecordService.recordSignal(signal, "IGNORED", "info-signal", null);
            return ResponseEntity.ok(Map.of("action", "INFO", "message", "已記錄，不執行下單"));
        }

        // 白名單檢查
        if (!riskConfig.isSymbolAllowed(signal.getSymbol())) {
            webhookService.sendNotification(
                    "⚠️ 風控攔截 — 交易對不在白名單",
                    "收到: " + signal.getSymbol() + "\n允許: " + riskConfig.getAllowedSymbols(),
                    DiscordWebhookService.COLOR_YELLOW);
            signalRecordService.recordSignal(signal, "REJECTED", "symbol-not-allowed", null);
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
            signalRecordService.recordSignal(signal, allSuccess ? "EXECUTED" : "FAILED", null, null);
            return ResponseEntity.ok(Map.of("action", "CLOSE", "results", results));
        }

        if (signal.getSignalType() == TradeSignal.SignalType.MOVE_SL) {
            List<OrderResult> results = binanceFuturesService.executeMoveSL(signal);
            boolean allSuccess = results.stream().allMatch(OrderResult::isSuccess);
            webhookService.sendNotification(
                    allSuccess ? "🔄 TP/SL 修改成功" : "❌ TP/SL 修改失敗",
                    formatMoveSLResults(signal, results),
                    allSuccess ? DiscordWebhookService.COLOR_BLUE : DiscordWebhookService.COLOR_RED);
            signalRecordService.recordSignal(signal, allSuccess ? "EXECUTED" : "FAILED", null, null);
            return ResponseEntity.ok(Map.of("action", "MOVE_SL", "results", results));
        }

        // ENTRY: 止損是必須的，不再自動補充預設值
        if (signal.getStopLoss() == 0) {
            webhookService.sendNotification(
                    "⚠️ 風控攔截 — 缺少止損",
                    signal.getSymbol() + " " + signal.getSide() + "\nENTRY 訊號必須包含 stop_loss",
                    DiscordWebhookService.COLOR_YELLOW);
            signalRecordService.recordSignal(signal, "REJECTED", "missing-stop-loss", null);
            return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 訊號必須包含 stop_loss"));
        }

        List<OrderResult> results = binanceFuturesService.executeSignal(signal);
        boolean entrySuccess = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
        webhookService.sendNotification(
                entrySuccess ? "✅ ENTRY 入場成功" : "❌ ENTRY 入場失敗",
                formatEntryResults(signal, results),
                entrySuccess ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
        // 嘗試取得關聯的 tradeId
        String tradeId = results.stream()
                .filter(r -> r.isSuccess() && r.getOrderId() != null)
                .map(OrderResult::getOrderId).findFirst().orElse(null);
        signalRecordService.recordSignal(signal, entrySuccess ? "EXECUTED" : "FAILED", null, tradeId);
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
                boolean isDca = request.getIsDca() != null && request.getIsDca();

                // DCA 時 side 可以為空（從現有持倉推斷），非 DCA 必須提供
                if (!isDca && request.getSide() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 需要 side (LONG/SHORT)"));
                }
                if (request.getEntryPrice() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 需要 entry_price"));
                }
                if (request.getStopLoss() == null && !isDca) {
                    return ResponseEntity.badRequest().body(Map.of("error", "ENTRY 必須包含 stop_loss"));
                }
                // DCA 止損容錯：AI 可能把止損放在 stop_loss 而非 new_stop_loss → 自動修正
                if (isDca && request.getNewStopLoss() == null) {
                    if (request.getStopLoss() != null) {
                        request.setNewStopLoss(request.getStopLoss());
                        log.info("DCA fallback: stop_loss {} 自動轉為 new_stop_loss", request.getStopLoss());
                    }
                    // 兩者都為 null 時（止損不變），讓 BinanceFuturesService 用現有 SL
                }

                TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                        .symbol(symbol)
                        .entryPriceLow(request.getEntryPrice())
                        .entryPriceHigh(request.getEntryPrice())
                        .signalType(TradeSignal.SignalType.ENTRY)
                        .isDca(isDca)
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .source(request.getSource());

                // side: DCA 可以為空（BinanceFuturesService 會從持倉推斷）
                if (request.getSide() != null) {
                    builder.side(TradeSignal.Side.valueOf(request.getSide().toUpperCase()));
                }

                // stopLoss: DCA 用 new_stop_loss（可能為 null，表示不改 SL → 用 0 代表），非 DCA 用 stop_loss
                if (isDca) {
                    builder.stopLoss(request.getNewStopLoss() != null ? request.getNewStopLoss() : 0);
                } else {
                    builder.stopLoss(request.getStopLoss());
                }

                TradeSignal signal = builder.build();

                // 設定 TP（如果有的話）
                if (request.getTakeProfit() != null) {
                    signal.setTakeProfits(List.of(request.getTakeProfit()));
                }

                List<OrderResult> results = binanceFuturesService.executeSignal(signal);
                boolean entryOk = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
                String title = isDca
                        ? (entryOk ? "📈 DCA 補倉成功 (API)" : "❌ DCA 補倉失敗 (API)")
                        : (entryOk ? "✅ ENTRY 入場成功 (API)" : "❌ ENTRY 入場失敗 (API)");
                webhookService.sendNotification(
                        title,
                        formatEntryResults(signal, results),
                        entryOk ? DiscordWebhookService.COLOR_GREEN : DiscordWebhookService.COLOR_RED);
                signalRecordService.recordSignal(signal, entryOk ? "EXECUTED" : "FAILED", null, null);
                return ResponseEntity.ok(Map.of("action", isDca ? "DCA" : "ENTRY", "results", results));
            }

            case "CLOSE": {
                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.CLOSE)
                        .closeRatio(request.getCloseRatio())
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .build();

                List<OrderResult> results = binanceFuturesService.executeClose(signal);
                boolean closeOk = !results.isEmpty() && results.get(0).isSuccess(); // 平倉單本身是否成功
                boolean allOk = results.stream().allMatch(OrderResult::isSuccess);   // 含 SL/TP 重掛
                String closeTitle;
                int closeColor;
                if (!closeOk) {
                    closeTitle = "❌ CLOSE 平倉失敗 (API)";
                    closeColor = DiscordWebhookService.COLOR_RED;
                } else if (!allOk) {
                    closeTitle = "⚠️ CLOSE 平倉成功，但 SL/TP 重掛異常 (API)";
                    closeColor = DiscordWebhookService.COLOR_YELLOW;
                } else {
                    closeTitle = "💰 CLOSE 平倉成功 (API)";
                    closeColor = DiscordWebhookService.COLOR_GREEN;
                }
                webhookService.sendNotification(closeTitle, formatCloseResults(symbol, results), closeColor);
                signalRecordService.recordFromRequest("CLOSE", symbol, null,
                        null, null, closeOk ? "EXECUTED" : "FAILED", null, null, request.getSource());
                return ResponseEntity.ok(Map.of("action", "CLOSE", "results", results));
            }

            case "MOVE_SL": {
                // 允許 newStopLoss=null（成本保護：「做保本處理」「止損上移至成本附近」）
                // BinanceFuturesService 會查 DB 開倉價當作 SL

                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.MOVE_SL)
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .build();

                List<OrderResult> results = binanceFuturesService.executeMoveSL(signal);
                boolean moveOk = results.stream().allMatch(OrderResult::isSuccess);
                webhookService.sendNotification(
                        moveOk ? "🔄 TP/SL 修改成功 (API)" : "❌ TP/SL 修改失敗 (API)",
                        formatMoveSLResults(signal, results),
                        moveOk ? DiscordWebhookService.COLOR_BLUE : DiscordWebhookService.COLOR_RED);
                signalRecordService.recordFromRequest("MOVE_SL", symbol, null,
                        null, null, moveOk ? "EXECUTED" : "FAILED", null, null, request.getSource());
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
                signalRecordService.recordFromRequest("CANCEL", symbol, null,
                        null, null, "EXECUTED", null, null, request.getSource());
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
    @SuppressWarnings("unchecked")
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody(required = false) Map<String, Object> body) {
        String status = (body != null && body.containsKey("status")) ? String.valueOf(body.get("status")) : "unknown";
        String aiStatus = (body != null && body.containsKey("aiStatus")) ? String.valueOf(body.get("aiStatus")) : null;
        Map<String, Object> aiTokenStats = (body != null && body.get("aiTokenStats") instanceof Map)
                ? (Map<String, Object>) body.get("aiTokenStats") : null;
        return ResponseEntity.ok(heartbeatService.receiveHeartbeat(status, aiStatus, aiTokenStats));
    }

    /**
     * 查詢 Monitor 連線狀態
     * GET /api/monitor-status
     */
    @GetMapping("/monitor-status")
    public ResponseEntity<Map<String, Object>> getMonitorStatus() {
        return ResponseEntity.ok(heartbeatService.getStatus());
    }

    /**
     * 查詢 User Data Stream WebSocket 連線狀態
     * GET /api/stream-status
     */
    @GetMapping("/stream-status")
    public ResponseEntity<Map<String, Object>> getStreamStatus() {
        return ResponseEntity.ok(userDataStreamService.getStatus());
    }

    // ==================== Admin ====================

    /**
     * 清理殭屍 OPEN 紀錄
     * POST /api/admin/cleanup-trades
     *
     * 比對 DB 中 OPEN 的 Trade 與幣安實際持倉，
     * 無持倉的標記為 CANCELLED (STALE_CLEANUP)
     */
    @PostMapping("/admin/cleanup-trades")
    public ResponseEntity<Map<String, Object>> cleanupTrades() {
        log.info("手動觸發殭屍 Trade 清理");
        Map<String, Object> result = tradeRecordService.cleanupStaleTrades(
                symbol -> binanceFuturesService.getCurrentPositionAmount(symbol));
        int cleaned = (int) result.get("cleaned");
        if (cleaned > 0) {
            webhookService.sendNotification(
                    "🧹 殭屍 Trade 清理完成",
                    String.format("清理: %d 筆 | 跳過: %d 筆\n來源: 手動 API", cleaned, result.get("skipped")),
                    DiscordWebhookService.COLOR_BLUE);
        }
        return ResponseEntity.ok(result);
    }

    // ==================== 訊號來源提取 ====================

    /**
     * 從 request body 中提取訊號來源元資料
     */
    @SuppressWarnings("unchecked")
    private SignalSource extractSource(Map<String, Object> body) {
        Object sourceObj = body.get("source");
        if (sourceObj instanceof Map) {
            Map<String, String> src = (Map<String, String>) sourceObj;
            return SignalSource.builder()
                    .platform(src.get("platform"))
                    .channelId(src.get("channel_id"))
                    .channelName(src.get("channel_name"))
                    .guildId(src.get("guild_id"))
                    .authorName(src.get("author_name"))
                    .messageId(src.get("message_id"))
                    .build();
        }
        return null;
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

        // 顯示訊號來源
        if (signal.getSource() != null) {
            SignalSource src = signal.getSource();
            sb.append("來源: ").append(src.getPlatform());
            if (src.getAuthorName() != null) {
                sb.append(" @").append(src.getAuthorName());
            }
            sb.append("\n");
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
                // 顯示 PnL（由 recordClose 回填）
                if (r.getTotalCommission() != null) {
                    sb.append(String.format("手續費: %.2f USDT\n", r.getTotalCommission()));
                }
                if (r.getNetProfit() != null) {
                    sb.append(String.format("已實現損益: %+.2f USDT\n", r.getNetProfit()));
                }
            } else {
                sb.append("✗ ").append(r.getErrorMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatMoveSLResults(TradeSignal signal, List<OrderResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(signal.getSymbol()).append("\n");
        if (signal.getNewStopLoss() != null && signal.getNewStopLoss() != 0) {
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

    // ==================== 多用戶廣播跟單 ====================

    /**
     * 廣播跟單給所有啟用自動跟單的用戶
     * POST /api/broadcast-trade
     * Body: { "action": "ENTRY", "symbol": "BTCUSDT", "side": "LONG", ... }
     *
     * 只廣播給 autoTradeEnabled=true 的用戶
     * 用 Thread Pool (10 個線程) 並行執行，約 2 秒內完成所有用戶
     */
    @PostMapping("/broadcast-trade")
    public ResponseEntity<?> broadcastTrade(@RequestBody TradeRequest request) {
        log.info("廣播跟單請求: action={} symbol={}", request.getAction(), request.getSymbol());

        // 驗證請求
        if (request.getAction() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "action 不可為空"));
        }
        String symbol = request.getSymbol();
        if (symbol == null || !riskConfig.isSymbolAllowed(symbol)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "交易對不在白名單",
                    "allowed", riskConfig.getAllowedSymbols().toString(),
                    "received", symbol != null ? symbol : "null"));
        }

        // 執行廣播
        Map<String, Object> result = broadcastTradeService.broadcastTrade(request);

        // 訊號記錄（廣播層級記一次，非 per-user）
        signalRecordService.recordFromRequest(
                request.getAction(), symbol, request.getSide(),
                request.getEntryPrice(), request.getStopLoss(),
                "EXECUTED", null, null, request.getSource());

        return ResponseEntity.ok(result);
    }
}
