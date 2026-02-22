package com.trader.trading.controller;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.service.*;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradeController 單元測試
 *
 * 覆蓋所有端點：
 * - parse-signal, execute-signal, execute-trade
 * - broadcast-trade, balance, positions, open-orders, exchange-info
 * - heartbeat, monitor-status, stream-status, admin/cleanup-trades
 * - trades, trades/{id}, trades/{id}/events, stats/summary
 */
class TradeControllerTest {

    private BinanceFuturesService binanceFuturesService;
    private BroadcastTradeService broadcastTradeService;
    private SignalParserService signalParserService;
    private RiskConfig riskConfig;
    private TradeRecordService tradeRecordService;
    private SignalDeduplicationService deduplicationService;
    private DiscordWebhookService webhookService;
    private MonitorHeartbeatService heartbeatService;
    private BinanceUserDataStreamService userDataStreamService;
    private SignalRecordService signalRecordService;

    private TradeController controller;

    @BeforeEach
    void setUp() {
        binanceFuturesService = mock(BinanceFuturesService.class);
        broadcastTradeService = mock(BroadcastTradeService.class);
        signalParserService = mock(SignalParserService.class);
        riskConfig = mock(RiskConfig.class);
        tradeRecordService = mock(TradeRecordService.class);
        deduplicationService = mock(SignalDeduplicationService.class);
        webhookService = mock(DiscordWebhookService.class);
        heartbeatService = mock(MonitorHeartbeatService.class);
        userDataStreamService = mock(BinanceUserDataStreamService.class);
        signalRecordService = mock(SignalRecordService.class);

        controller = new TradeController(
                binanceFuturesService, broadcastTradeService, signalParserService,
                riskConfig, tradeRecordService, deduplicationService,
                webhookService, heartbeatService, userDataStreamService, signalRecordService);

        // 預設白名單通過
        when(riskConfig.isSymbolAllowed(anyString())).thenReturn(true);
        when(riskConfig.getAllowedSymbols()).thenReturn(List.of("BTCUSDT", "ETHUSDT"));
    }

    // ==================== 解析訊號 ====================

    @Nested
    @DisplayName("POST /api/parse-signal")
    class ParseSignalTests {

        @Test
        @DisplayName("解析成功 — 回傳 TradeSignal")
        void parseSignal_success() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").side(TradeSignal.Side.LONG).entryPriceLow(95000).build();
            when(signalParserService.parse("some message")).thenReturn(Optional.of(signal));

            ResponseEntity<?> response = controller.parseSignal(Map.of("message", "some message"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(signal);
        }

        @Test
        @DisplayName("解析失敗 — 回傳 400")
        void parseSignal_fail() {
            when(signalParserService.parse(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.parseSignal(Map.of("message", "not a signal"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    // ==================== 執行訊號 (execute-signal) ====================

    @Nested
    @DisplayName("POST /api/execute-signal")
    class ExecuteSignalTests {

        @Test
        @DisplayName("解析失敗 — IGNORED")
        void parseFailReturnsIgnored() {
            when(signalParserService.parse(any())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "random text"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "IGNORED");
        }

        @Test
        @DisplayName("CANCEL 訊號 — 成功取消")
        void cancelSignalSuccess() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CANCEL).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(deduplicationService.isCancelDuplicate("BTCUSDT")).thenReturn(false);
            when(binanceFuturesService.cancelAllOrders("BTCUSDT")).thenReturn("OK");

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "cancel btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "CANCEL");
            verify(webhookService).sendNotification(contains("CANCEL"), anyString(), anyInt());
        }

        @Test
        @DisplayName("CANCEL 重複 — 跳過")
        void cancelDuplicateSkipped() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CANCEL).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(deduplicationService.isCancelDuplicate("BTCUSDT")).thenReturn(true);

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "cancel btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(binanceFuturesService, never()).cancelAllOrders(any());
        }

        @Test
        @DisplayName("INFO 訊號 — 記錄但不下單")
        void infoSignalIgnored() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.INFO).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "info msg"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "INFO");
            verify(binanceFuturesService, never()).executeSignal(any());
        }

        @Test
        @DisplayName("白名單攔截 — 400")
        void symbolNotAllowed() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("DOGEUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG).entryPriceLow(0.3).stopLoss(0.25).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(riskConfig.isSymbolAllowed("DOGEUSDT")).thenReturn(false);

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "buy doge"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("CLOSE 全倉 — 成功")
        void closeFullSuccess() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CLOSE).closeRatio(1.0).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(binanceFuturesService.executeClose(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "close btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "CLOSE");
        }

        @Test
        @DisplayName("MOVE_SL — 成功")
        void moveSLSuccess() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(96000.0).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(binanceFuturesService.executeMoveSL(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).type("STOP_MARKET").build()));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "move sl"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "MOVE_SL");
        }

        @Test
        @DisplayName("ENTRY 缺少止損 — 400")
        void entryMissingStopLoss() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG).entryPriceLow(95000).stopLoss(0).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "buy btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("ENTRY 成功 — 帶止損")
        void entrySuccess() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG).entryPriceLow(95000).stopLoss(94000).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("12345").quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "buy btc 95000"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "ENTRY");
        }
    }

    // ==================== 結構化交易 (execute-trade) ====================

    @Nested
    @DisplayName("POST /api/execute-trade")
    class ExecuteTradeTests {

        @Test
        @DisplayName("action 為空 — 400")
        void nullAction() {
            TradeRequest request = new TradeRequest();
            request.setSymbol("BTCUSDT");

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("symbol 不在白名單 — 400")
        void symbolNotAllowed() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("DOGEUSDT");
            when(riskConfig.isSymbolAllowed("DOGEUSDT")).thenReturn(false);

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("ENTRY 缺 side — 400")
        void entryMissingSide() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // side is null

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("ENTRY 缺 entry_price — 400")
        void entryMissingPrice() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setStopLoss(94000.0);
            // entryPrice is null

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("ENTRY 做多成功")
        void longEntrySuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);

            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("123").quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "ENTRY");
        }

        @Test
        @DisplayName("DCA 補倉成功 — side 可為 null")
        void dcaEntrySuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setIsDca(true);
            request.setEntryPrice(93000.0);
            // side null, stopLoss null → DCA 容許

            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("456").quantity(0.005).price(93000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "DCA");
        }

        @Test
        @DisplayName("CLOSE 成功")
        void closeSuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            request.setSymbol("BTCUSDT");
            request.setCloseRatio(0.5);

            when(binanceFuturesService.executeClose(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).quantity(0.005).price(96000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("MOVE_SL 成功")
        void moveSLSuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("MOVE_SL");
            request.setSymbol("BTCUSDT");
            request.setNewStopLoss(95500.0);

            when(binanceFuturesService.executeMoveSL(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).type("STOP_MARKET").build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("CANCEL 成功")
        void cancelSuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("CANCEL");
            request.setSymbol("BTCUSDT");

            when(deduplicationService.isCancelDuplicate("BTCUSDT")).thenReturn(false);
            when(binanceFuturesService.cancelAllOrders("BTCUSDT")).thenReturn("OK");

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("不支援的 action — 400")
        void unsupportedAction() {
            TradeRequest request = new TradeRequest();
            request.setAction("UNKNOWN");
            request.setSymbol("BTCUSDT");

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    // ==================== 查詢類 API ====================

    @Nested
    @DisplayName("查詢類 API")
    class QueryTests {

        @Test
        @DisplayName("GET /api/balance — 成功")
        void getBalance() {
            when(binanceFuturesService.getAccountBalance()).thenReturn("{\"balance\":\"1000\"}");
            ResponseEntity<String> response = controller.getBalance();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("balance");
        }

        @Test
        @DisplayName("GET /api/positions — 成功")
        void getPositions() {
            when(binanceFuturesService.getPositions()).thenReturn("[{\"symbol\":\"BTCUSDT\"}]");
            ResponseEntity<String> response = controller.getPositions();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/exchange-info — 成功")
        void getExchangeInfo() {
            when(binanceFuturesService.getExchangeInfo()).thenReturn("{\"symbols\":[]}");
            ResponseEntity<String> response = controller.getExchangeInfo();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/open-orders — 成功")
        void getOpenOrders() {
            when(binanceFuturesService.getOpenOrders("BTCUSDT")).thenReturn("[]");
            ResponseEntity<String> response = controller.getOpenOrders("BTCUSDT");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/trades — 全部")
        void getTradesAll() {
            when(tradeRecordService.findAll()).thenReturn(List.of());
            ResponseEntity<List<Trade>> response = controller.getTrades(null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/trades?status=OPEN — 篩選")
        void getTradesFiltered() {
            when(tradeRecordService.findByStatus("OPEN")).thenReturn(List.of());
            ResponseEntity<List<Trade>> response = controller.getTrades("OPEN");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(tradeRecordService).findByStatus("OPEN");
        }

        @Test
        @DisplayName("GET /api/trades/{id} — 找到")
        void getTradeDetailFound() {
            Trade trade = Trade.builder().tradeId("t1").symbol("BTCUSDT").build();
            when(tradeRecordService.findById("t1")).thenReturn(Optional.of(trade));

            ResponseEntity<?> response = controller.getTradeDetail("t1");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/trades/{id} — 找不到 404")
        void getTradeDetailNotFound() {
            when(tradeRecordService.findById("nope")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getTradeDetail("nope");
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("GET /api/trades/{id}/events")
        void getTradeEvents() {
            when(tradeRecordService.findEvents("t1")).thenReturn(List.of());
            ResponseEntity<List<TradeEvent>> response = controller.getTradeEvents("t1");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/stats/summary")
        void getStatsSummary() {
            when(tradeRecordService.getStatsSummary()).thenReturn(Map.of("winRate", 0.6));
            ResponseEntity<Map<String, Object>> response = controller.getStatsSummary();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    // ==================== 監控類 API ====================

    @Nested
    @DisplayName("監控類 API")
    class MonitorTests {

        @Test
        @DisplayName("POST /api/heartbeat")
        void heartbeat() {
            when(heartbeatService.receiveHeartbeat("connected", null, null))
                    .thenReturn(Map.of("received", true));

            ResponseEntity<Map<String, Object>> response =
                    controller.heartbeat(Map.of("status", "connected"));
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("POST /api/heartbeat — body 為 null")
        void heartbeatNullBody() {
            when(heartbeatService.receiveHeartbeat("unknown", null, null))
                    .thenReturn(Map.of("received", true));

            ResponseEntity<Map<String, Object>> response = controller.heartbeat(null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/monitor-status")
        void monitorStatus() {
            when(heartbeatService.getStatus()).thenReturn(Map.of("connected", true));
            ResponseEntity<Map<String, Object>> response = controller.getMonitorStatus();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /api/stream-status")
        void streamStatus() {
            when(userDataStreamService.getStatus()).thenReturn(Map.of("connected", false));
            ResponseEntity<Map<String, Object>> response = controller.getStreamStatus();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    // ==================== Admin ====================

    @Nested
    @DisplayName("Admin 端點")
    class AdminTests {

        @Test
        @DisplayName("POST /api/admin/cleanup-trades — 有清理")
        void cleanupWithResults() {
            when(tradeRecordService.cleanupStaleTrades(any()))
                    .thenReturn(Map.of("cleaned", 3, "skipped", 1));

            ResponseEntity<Map<String, Object>> response = controller.cleanupTrades();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(webhookService).sendNotification(contains("殭屍"), anyString(), anyInt());
        }

        @Test
        @DisplayName("POST /api/admin/cleanup-trades — 無清理")
        void cleanupNoResults() {
            when(tradeRecordService.cleanupStaleTrades(any()))
                    .thenReturn(Map.of("cleaned", 0, "skipped", 0));

            ResponseEntity<Map<String, Object>> response = controller.cleanupTrades();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }
    }

    // ==================== 廣播跟單 ====================

    @Nested
    @DisplayName("POST /api/broadcast-trade")
    class BroadcastTests {

        @Test
        @DisplayName("廣播成功")
        void broadcastSuccess() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 5, "success", 5));

            ResponseEntity<?> response = controller.broadcastTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("action 為空 — 400")
        void broadcastNoAction() {
            TradeRequest request = new TradeRequest();
            request.setSymbol("BTCUSDT");

            ResponseEntity<?> response = controller.broadcastTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("symbol 不在白名單 — 400")
        void broadcastInvalidSymbol() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("DOGEUSDT");
            when(riskConfig.isSymbolAllowed("DOGEUSDT")).thenReturn(false);

            ResponseEntity<?> response = controller.broadcastTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    // ==================== 其他端點 ====================

    @Nested
    @DisplayName("其他端點")
    class MiscTests {

        @Test
        @DisplayName("POST /api/leverage")
        void setLeverage() {
            when(binanceFuturesService.setLeverage("BTCUSDT", 10)).thenReturn("{\"leverage\":10}");

            Map<String, Object> body = new HashMap<>();
            body.put("symbol", "BTCUSDT");
            body.put("leverage", 10);

            ResponseEntity<String> response = controller.setLeverage(body);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("DELETE /api/orders")
        void cancelAllOrders() {
            when(binanceFuturesService.cancelAllOrders("BTCUSDT")).thenReturn("OK");
            ResponseEntity<String> response = controller.cancelAllOrders("BTCUSDT");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
