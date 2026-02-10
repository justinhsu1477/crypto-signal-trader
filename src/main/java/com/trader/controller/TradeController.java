package com.trader.controller;

import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import com.trader.service.BinanceFuturesService;
import com.trader.service.SignalParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.trader.config.RiskConfig;

import java.util.ArrayList;
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

        // 補充預設止損 (入場價的 defaultSlPercent%)
        if (signal.getStopLoss() == 0) {
            double entry = signal.getEntryPriceLow();
            double slPercent = riskConfig.getDefaultSlPercent() / 100.0;
            double defaultSl = signal.getSide() == TradeSignal.Side.LONG
                    ? entry * (1 - slPercent)   // 做多止損在下方
                    : entry * (1 + slPercent);   // 做空止損在上方
            signal.setStopLoss(defaultSl);
            log.info("未設定止損, 使用預設 {}%: {}", riskConfig.getDefaultSlPercent(), defaultSl);
        }

        // 補充預設止盈 (入場價的 defaultTpPercent%)
        if (signal.getTakeProfits() == null || signal.getTakeProfits().isEmpty()) {
            double entry = signal.getEntryPriceLow();
            double tpPercent = riskConfig.getDefaultTpPercent() / 100.0;
            double defaultTp = signal.getSide() == TradeSignal.Side.LONG
                    ? entry * (1 + tpPercent)   // 做多止盈在上方
                    : entry * (1 - tpPercent);   // 做空止盈在下方
            signal.setTakeProfits(new ArrayList<>(List.of(defaultTp)));
            log.info("未設定止盈, 使用預設 {}%: {}", riskConfig.getDefaultTpPercent(), defaultTp);
        }

        List<OrderResult> results = binanceFuturesService.executeSignal(signal);
        return ResponseEntity.ok(results);
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
