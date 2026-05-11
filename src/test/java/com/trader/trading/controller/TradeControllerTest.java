package com.trader.trading.controller;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.SignalSource;
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
    private SymbolLockRegistry symbolLockRegistry;
    private com.trader.trading.config.MultiUserConfig multiUserConfig;

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
        symbolLockRegistry = new SymbolLockRegistry();
        multiUserConfig = mock(com.trader.trading.config.MultiUserConfig.class);
        // 預設多用戶模式關閉 — 既有測試聚焦單用戶行為
        when(multiUserConfig.isEnabled()).thenReturn(false);

        controller = new TradeController(
                binanceFuturesService, broadcastTradeService, signalParserService,
                riskConfig, tradeRecordService, deduplicationService,
                webhookService, heartbeatService, userDataStreamService, signalRecordService,
                symbolLockRegistry, multiUserConfig);

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
        @DisplayName("CANCEL 失敗 — cancelAllOrders 異常應向上傳播")
        void cancelFailurePropagates() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CANCEL).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(deduplicationService.isCancelDuplicate("BTCUSDT")).thenReturn(false);
            when(binanceFuturesService.cancelAllOrders("BTCUSDT"))
                    .thenThrow(new RuntimeException("Binance API error"));

            assertThatThrownBy(() -> controller.executeSignal(Map.of("message", "cancel btc")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Binance API error");
        }

        @Test
        @DisplayName("CANCEL 成功但 recordCancel 失敗 — 不影響取消結果")
        void cancelSuccessEvenIfRecordFails() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CANCEL).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(deduplicationService.isCancelDuplicate("BTCUSDT")).thenReturn(false);
            when(binanceFuturesService.cancelAllOrders("BTCUSDT")).thenReturn("OK");
            doThrow(new RuntimeException("DB error")).when(tradeRecordService).recordCancel("BTCUSDT");

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "cancel btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "CANCEL");
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

    // ==================== execute-signal × multi-user 模式 ====================

    @Nested
    @DisplayName("POST /api/execute-signal × multi-user")
    class ExecuteSignalMultiUserTests {

        @Test
        @DisplayName("multi-user 開啟 + ENTRY → 自動轉廣播，不走單用戶 executeSignal")
        void multiUserEntryDelegatesToBroadcast() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG).entryPriceLow(95000).stopLoss(94000)
                    .takeProfits(List.of(97000.0)).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("status", "COMPLETED", "successCount", 11));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "buy btc"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 不走單用戶路徑
            verify(binanceFuturesService, never()).executeSignal(any());
            // 走廣播，且 action 正確對應
            org.mockito.ArgumentCaptor<TradeRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(TradeRequest.class);
            verify(broadcastTradeService).broadcastTrade(captor.capture());
            TradeRequest sent = captor.getValue();
            assertThat(sent.getAction()).isEqualTo("ENTRY");
            assertThat(sent.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(sent.getSide()).isEqualTo("LONG");
            assertThat(sent.getEntryPrice()).isEqualTo(95000.0);
            assertThat(sent.getStopLoss()).isEqualTo(94000.0);
            assertThat(sent.getTakeProfit()).isEqualTo(97000.0);  // 取第一個 TP
            assertThat(sent.getSignalTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("multi-user 開啟 + CLOSE → 轉廣播，不走單用戶 executeClose")
        void multiUserCloseDelegatesToBroadcast() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CLOSE).closeRatio(1.0).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("status", "COMPLETED"));

            controller.executeSignal(Map.of("message", "close btc"));

            verify(binanceFuturesService, never()).executeClose(any());
            verify(broadcastTradeService).broadcastTrade(
                    org.mockito.ArgumentMatchers.argThat(r -> "CLOSE".equals(r.getAction())
                            && Double.valueOf(1.0).equals(r.getCloseRatio())));
        }

        @Test
        @DisplayName("multi-user 開啟 + MOVE_SL → 轉廣播，帶 newStopLoss/newTakeProfit")
        void multiUserMoveSlDelegatesToBroadcast() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(96000.0).newTakeProfit(99000.0).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(broadcastTradeService.broadcastTrade(any())).thenReturn(Map.of("status", "COMPLETED"));

            controller.executeSignal(Map.of("message", "move sl"));

            verify(binanceFuturesService, never()).executeMoveSL(any());
            verify(broadcastTradeService).broadcastTrade(
                    org.mockito.ArgumentMatchers.argThat(r -> "MOVE_SL".equals(r.getAction())
                            && Double.valueOf(96000.0).equals(r.getNewStopLoss())
                            && Double.valueOf(99000.0).equals(r.getNewTakeProfit())));
        }

        @Test
        @DisplayName("multi-user 開啟 + CANCEL → 轉廣播，不走單用戶 cancelAllOrders")
        void multiUserCancelDelegatesToBroadcast() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.CANCEL).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(broadcastTradeService.broadcastTrade(any())).thenReturn(Map.of("status", "COMPLETED"));

            controller.executeSignal(Map.of("message", "cancel btc"));

            verify(binanceFuturesService, never()).cancelAllOrders(any());
            verify(broadcastTradeService).broadcastTrade(
                    org.mockito.ArgumentMatchers.argThat(r -> "CANCEL".equals(r.getAction())));
        }

        @Test
        @DisplayName("multi-user 開啟 + INFO → 記錄但不廣播（與單用戶行為一致）")
        void multiUserInfoDoesNotBroadcast() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.INFO).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));

            ResponseEntity<?> response = controller.executeSignal(Map.of("message", "info"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("action", "INFO");
            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        @Test
        @DisplayName("multi-user 關閉 → 維持原單用戶路徑（回歸 regression 保護）")
        void singleUserPathPreserved() {
            when(multiUserConfig.isEnabled()).thenReturn(false);
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG).entryPriceLow(95000).stopLoss(94000).build();
            when(signalParserService.parse(any())).thenReturn(Optional.of(signal));
            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("x1").build()));

            controller.executeSignal(Map.of("message", "buy btc"));

            verify(binanceFuturesService).executeSignal(any());
            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        @Test
        @DisplayName("buildBroadcastRequestFromSignal — 解析失敗 sentinel 值（0）不填入 TradeRequest")
        void converterHandlesZeroSentinels() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(0).stopLoss(0)  // 0 表示未設定
                    .build();

            TradeRequest req = controller.buildBroadcastRequestFromSignal(signal);

            assertThat(req.getAction()).isEqualTo("ENTRY");
            assertThat(req.getEntryPrice()).isNull();
            assertThat(req.getStopLoss()).isNull();
            assertThat(req.getTakeProfit()).isNull();  // takeProfits 未設為 null
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

        @Test
        @DisplayName("訊號過期（超過 5 分鐘）— 400 REJECTED_STALE + Admin 通知")
        void staleSignal_rejected() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 10 分鐘前的時間戳
            request.setSignalTimestamp(System.currentTimeMillis() - 10 * 60 * 1000L);

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().toString()).contains("REJECTED_STALE");
            // 驗證發送紅色 Admin 通知（🚫 過期訊號已攔截）
            verify(webhookService).sendNotificationToAdmins(
                    contains("過期訊號已攔截"),
                    contains("已拒絕"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("訊號延遲（30s~5min）— 仍執行 + Admin 警告通知")
        void delayedSignal_executesWithWarning() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 2 分鐘前的時間戳（30s < 120s < 5min）
            request.setSignalTimestamp(System.currentTimeMillis() - 120_000L);

            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("123").quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            // 仍正常執行
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 驗證發送黃色 Admin 通知（⚠️ 訊號延遲偏高）
            verify(webhookService).sendNotificationToAdmins(
                    contains("訊號延遲偏高"),
                    contains("仍執行"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("訊號新鮮（30 秒內）— 正常執行，無 Admin 通知")
        void freshSignal_accepted() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 10 秒前的時間戳
            request.setSignalTimestamp(System.currentTimeMillis() - 10_000L);

            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("123").quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 新鮮訊號不應發送任何 Admin 通知
            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("未提供 signalTimestamp — 向後相容，正常執行，無 Admin 通知")
        void noTimestamp_backwardCompatible() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 不設 signalTimestamp

            when(binanceFuturesService.executeSignal(any())).thenReturn(
                    List.of(OrderResult.builder().success(true).orderId("123").quantity(0.01).price(95000).build()));

            ResponseEntity<?> response = controller.executeTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 無時間戳不應發送 Admin 通知
            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
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
            when(heartbeatService.receiveHeartbeat(eq("connected"), isNull(), isNull(), isNull()))
                    .thenReturn(Map.of("received", true));

            ResponseEntity<Map<String, Object>> response =
                    controller.heartbeat(Map.of("status", "connected"));
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("POST /api/heartbeat — body 為 null")
        void heartbeatNullBody() {
            when(heartbeatService.receiveHeartbeat(eq("unknown"), isNull(), isNull(), isNull()))
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

        @Test
        @DisplayName("CLOSE 不走 signal-level 去重 — 分批平倉不應被攔截")
        void broadcastCloseBypassesDedup() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            request.setSymbol("BTCUSDT");

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // CLOSE 不應呼叫 isSignalProcessed，直接執行廣播
            verify(deduplicationService, never()).isSignalProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("MOVE_SL 不走 signal-level 去重 — 連續調整止損不應被攔截")
        void broadcastMoveSLBypassesDedup() {
            TradeRequest request = new TradeRequest();
            request.setAction("MOVE_SL");
            request.setSymbol("BTCUSDT");

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // MOVE_SL 不應呼叫 isSignalProcessed，直接執行廣播
            verify(deduplicationService, never()).isSignalProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("CLOSE 通過去重 — 正常廣播")
        void broadcastClosePassesDedup() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            request.setSymbol("BTCUSDT");

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("ENTRY 重複訊號 — signal-level 去重攔截")
        void broadcastEntryDuplicate() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);

            when(deduplicationService.isSignalProcessed(any())).thenReturn(true);

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("status", "SKIPPED");
            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        // ====== message_id 永久去重（Queue Replay 防重複下單） ======

        @Test
        @DisplayName("message_id 已存在 → SKIPPED（防止 Queue Replay 重複下單）")
        void broadcastMessageIdDuplicate_skipped() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            request.setSource(SignalSource.builder()
                    .platform("DISCORD").messageId("msg-123").build());

            when(signalRecordService.isMessageIdProcessed("msg-123")).thenReturn(true);

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("status", "SKIPPED");
            assertThat(body.get("reason").toString()).contains("message_id");
            // 不應進入 signal-level 去重或廣播
            verify(deduplicationService, never()).isSignalProcessed(any());
            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        @Test
        @DisplayName("message_id 不存在（新訊號）→ 正常通過廣播")
        void broadcastMessageIdNew_passesThrough() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            request.setSource(SignalSource.builder()
                    .platform("DISCORD").messageId("msg-new").build());

            when(signalRecordService.isMessageIdProcessed("msg-new")).thenReturn(false);
            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("source 為 null → 跳過 message_id 檢查，正常廣播")
        void broadcastNullSource_skipsMessageIdCheck() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // source is null

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(signalRecordService, never()).isMessageIdProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("message_id 為空白 → 跳過 message_id 檢查，正常廣播")
        void broadcastBlankMessageId_skipsCheck() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            request.setSource(SignalSource.builder()
                    .platform("DISCORD").messageId("  ").build());

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(signalRecordService, never()).isMessageIdProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("CANCEL 跳過 signal-level 去重 — 有自己的去重邏輯")
        void broadcastCancelSkipsSignalDedup() {
            TradeRequest request = new TradeRequest();
            request.setAction("CANCEL");
            request.setSymbol("BTCUSDT");

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 3, "success", 3));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(deduplicationService, never()).isSignalProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("廣播訊號過期 — 400 + 記錄到 signals 表 + Admin 通知")
        void broadcastStaleSignal_rejected() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 6 分鐘前的時間戳
            request.setSignalTimestamp(System.currentTimeMillis() - 6 * 60 * 1000L);

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().toString()).contains("REJECTED_STALE");
            // 驗證過期訊號被記錄到 signals 表
            verify(signalRecordService).recordFromRequest(
                    eq("ENTRY"), eq("BTCUSDT"), eq("LONG"),
                    eq(95000.0), eq(94000.0),
                    eq("REJECTED"), contains("signal-stale"), isNull(), isNull());
            verify(broadcastTradeService, never()).broadcastTrade(any());
            // 驗證發送紅色 Admin 通知
            verify(webhookService).sendNotificationToAdmins(
                    contains("過期訊號已攔截"),
                    contains("已拒絕"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("廣播訊號延遲（30s~5min）— 仍廣播 + Admin 警告通知")
        void broadcastDelayedSignal_executesWithWarning() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 90 秒前的時間戳
            request.setSignalTimestamp(System.currentTimeMillis() - 90_000L);

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 5, "success", 5));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            // 仍正常廣播
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(broadcastTradeService).broadcastTrade(any());
            // 驗證發送黃色 Admin 通知
            verify(webhookService).sendNotificationToAdmins(
                    contains("訊號延遲偏高"),
                    contains("仍執行"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("廣播訊號新鮮 — 正常廣播，無 Admin 通知")
        void broadcastFreshSignal_accepted() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);
            // 設定 5 秒前的時間戳
            request.setSignalTimestamp(System.currentTimeMillis() - 5_000L);

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("total", 5, "success", 5));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(broadcastTradeService).broadcastTrade(any());
            // 新鮮訊號不應發送 Admin 通知
            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        // ====== Admin Dashboard 補發訊號場景 ======

        @Test
        @DisplayName("Admin Dashboard CLOSE 訊號 — 無 signalTimestamp 不觸發時效驗證")
        void adminDashboardClose_noTimestamp() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            request.setSymbol("BTCUSDT");
            request.setCloseRatio(1.0);
            // Admin Dashboard 不帶 signalTimestamp

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of(
                            "status", "COMPLETED",
                            "totalUsers", 8,
                            "successCount", 7,
                            "failCount", 1,
                            "skippedNoSubscription", 2,
                            "skippedNoApiKey", 1));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(broadcastTradeService).broadcastTrade(any());
            // 無 Admin 通知（signalTimestamp=null 跳過時效檢查）
            verify(webhookService, never()).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Admin Dashboard ENTRY 訊號含 Source — 記錄到 signals 表")
        void adminDashboardEntry_recordsSignal() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(93000.0);
            request.setSource(SignalSource.builder()
                    .platform("ADMIN_DASHBOARD")
                    .authorName("admin@hookfi.com")
                    .build());

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("status", "COMPLETED", "totalUsers", 5, "successCount", 5));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 驗證 signalRecord 記錄含 source
            verify(signalRecordService).recordFromRequest(
                    eq("ENTRY"), eq("BTCUSDT"), eq("LONG"),
                    eq(95000.0), eq(93000.0),
                    eq("EXECUTED"), isNull(), isNull(), any(SignalSource.class));
        }

        @Test
        @DisplayName("廣播回傳完整多用戶統計 — 包含 skipped 計數")
        void broadcastReturnsFullMultiUserStats() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            request.setSymbol("BTCUSDT");

            Map<String, Object> broadcastResult = Map.of(
                    "status", "COMPLETED",
                    "totalUsers", 6,
                    "successCount", 5,
                    "failCount", 1,
                    "skippedNoSubscription", 3,
                    "skippedNoApiKey", 2);
            when(broadcastTradeService.broadcastTrade(any())).thenReturn(broadcastResult);

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("status")).isEqualTo("COMPLETED");
            assertThat(body.get("totalUsers")).isEqualTo(6);
            assertThat(body.get("successCount")).isEqualTo(5);
            assertThat(body.get("failCount")).isEqualTo(1);
            assertThat(body.get("skippedNoSubscription")).isEqualTo(3);
            assertThat(body.get("skippedNoApiKey")).isEqualTo(2);
        }

        @Test
        @DisplayName("MOVE_SL 廣播 — 跳過去重 + 執行廣播")
        void broadcastMoveSL_skipsDedup() {
            TradeRequest request = new TradeRequest();
            request.setAction("MOVE_SL");
            request.setSymbol("BTCUSDT");
            request.setNewStopLoss(94500.0);

            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("status", "COMPLETED", "totalUsers", 5, "successCount", 5));

            ResponseEntity<?> response = controller.broadcastTrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(deduplicationService, never()).isSignalProcessed(any());
            verify(broadcastTradeService).broadcastTrade(any());
        }

        @Test
        @DisplayName("symbol 為 null — 回 400")
        void broadcastNullSymbol_returns400() {
            TradeRequest request = new TradeRequest();
            request.setAction("CLOSE");
            // symbol = null

            ResponseEntity<?> response = controller.broadcastTrade(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        // ====== 訊號記錄時序（防重複下單） ======

        @Test
        @DisplayName("ENTRY 廣播 → signalRecord 在 broadcastTrade 之前寫入（防 race condition）")
        void broadcastEntry_signalRecordBeforeBroadcast() {
            TradeRequest request = new TradeRequest();
            request.setAction("ENTRY");
            request.setSymbol("BTCUSDT");
            request.setSide("LONG");
            request.setEntryPrice(95000.0);
            request.setStopLoss(94000.0);

            when(deduplicationService.isSignalProcessed(any())).thenReturn(false);
            when(broadcastTradeService.broadcastTrade(any()))
                    .thenReturn(Map.of("status", "COMPLETED", "totalUsers", 3, "successCount", 3));

            controller.broadcastTrade(request);

            // 用 InOrder 驗證 recordFromRequest 在 broadcastTrade 之前被呼叫
            var inOrder = inOrder(signalRecordService, broadcastTradeService);
            inOrder.verify(signalRecordService).recordFromRequest(
                    eq("ENTRY"), eq("BTCUSDT"), eq("LONG"),
                    eq(95000.0), eq(94000.0),
                    eq("EXECUTED"), isNull(), isNull(), any());
            inOrder.verify(broadcastTradeService).broadcastTrade(any());
        }
    }

    // ==================== extractSource（圖訊號 audit chain） ====================

    @Nested
    @DisplayName("extractSource — source.attachment.sha256 audit chain")
    class ExtractSourceTests {

        /** 用 reflection 呼叫 private extractSource。 */
        @SuppressWarnings("unchecked")
        private SignalSource invokeExtractSource(Map<String, Object> body) throws Exception {
            var method = TradeController.class.getDeclaredMethod("extractSource", Map.class);
            method.setAccessible(true);
            return (SignalSource) method.invoke(controller, body);
        }

        @Test
        @DisplayName("source.attachment.sha256 從 nested map 提取並設到 SignalSource")
        void extractSource_attachmentSha256_nested() throws Exception {
            Map<String, Object> attachment = Map.of(
                    "url", "https://cdn.discordapp.com/x.png",
                    "filename", "chart.png",
                    "content_type", "image/png",
                    "sha256", "abc123def456",
                    "size", 102400);
            Map<String, Object> source = new HashMap<>();
            source.put("platform", "DISCORD");
            source.put("message_id", "msg-1");
            source.put("attachment", attachment);
            Map<String, Object> body = Map.of("source", source);

            SignalSource result = invokeExtractSource(body);

            assertThat(result).isNotNull();
            assertThat(result.getPlatform()).isEqualTo("DISCORD");
            assertThat(result.getMessageId()).isEqualTo("msg-1");
            assertThat(result.getAttachmentSha256()).isEqualTo("abc123def456");
        }

        @Test
        @DisplayName("body.attachment 頂層格式（向後相容）也能取到 sha256")
        void extractSource_attachmentSha256_topLevel() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("source", Map.of("platform", "DISCORD", "message_id", "msg-2"));
            body.put("attachment", Map.of("sha256", "topsha999"));

            SignalSource result = invokeExtractSource(body);

            assertThat(result).isNotNull();
            assertThat(result.getAttachmentSha256()).isEqualTo("topsha999");
        }

        @Test
        @DisplayName("無 attachment — attachmentSha256 為 null（文字訊號路徑）")
        void extractSource_noAttachment() throws Exception {
            Map<String, Object> body = Map.of(
                    "source", Map.of("platform", "DISCORD", "message_id", "msg-3"));

            SignalSource result = invokeExtractSource(body);

            assertThat(result).isNotNull();
            assertThat(result.getAttachmentSha256()).isNull();
        }

        @Test
        @DisplayName("source 為 null — 回傳 null（保留原行為）")
        void extractSource_noSource() throws Exception {
            Map<String, Object> body = Map.of("message", "buy btc");

            SignalSource result = invokeExtractSource(body);

            assertThat(result).isNull();
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
