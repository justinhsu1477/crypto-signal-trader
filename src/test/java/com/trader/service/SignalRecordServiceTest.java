package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.entity.Signal;
import com.trader.trading.repository.SignalRepository;
import com.trader.trading.service.SignalDeduplicationService;
import com.trader.trading.service.SignalRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SignalRecordServiceTest {

    private SignalRepository signalRepository;
    private SignalDeduplicationService deduplicationService;
    private ObjectMapper objectMapper;
    private SignalRecordService service;

    @BeforeEach
    void setUp() {
        signalRepository = mock(SignalRepository.class);
        deduplicationService = mock(SignalDeduplicationService.class);
        objectMapper = new ObjectMapper();
        service = new SignalRecordService(signalRepository, deduplicationService, objectMapper);

        when(signalRepository.save(any(Signal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("recordSignal")
    class RecordSignal {

        @Test
        @DisplayName("正常 ENTRY 訊號 -> 完整記錄到 DB")
        void normalEntrySignal_recordedCorrectly() {
            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(60000.0)
                    .entryPriceHigh(61000.0)
                    .stopLoss(58000.0)
                    .takeProfits(List.of(62000.0, 63000.0))
                    .leverage(10)
                    .rawMessage("BTC Long 60000-61000 SL 58000")
                    .isDca(false)
                    .build();

            when(deduplicationService.generateHash(signal)).thenReturn("hash-abc");

            service.recordSignal(signal, "EXECUTED", null, "trade-001");

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            Signal saved = captor.getValue();

            assertThat(saved.getSignalId()).isNotNull();
            assertThat(saved.getAction()).isEqualTo("ENTRY");
            assertThat(saved.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(saved.getSide()).isEqualTo("LONG");
            assertThat(saved.getEntryPriceLow()).isEqualTo(60000.0);
            assertThat(saved.getEntryPriceHigh()).isEqualTo(61000.0);
            assertThat(saved.getStopLoss()).isEqualTo(58000.0);
            assertThat(saved.getTakeProfits()).contains("62000");
            assertThat(saved.getLeverage()).isEqualTo(10);
            assertThat(saved.getSignalHash()).isEqualTo("hash-abc");
            assertThat(saved.getExecutionStatus()).isEqualTo("EXECUTED");
            assertThat(saved.getRejectionReason()).isNull();
            assertThat(saved.getTradeId()).isEqualTo("trade-001");
        }

        @Test
        @DisplayName("DCA 訊號 -> action 為 DCA")
        void dcaSignal_actionIsDca() {
            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("ETHUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(true)
                    .build();

            when(deduplicationService.generateHash(signal)).thenReturn("hash-dca");

            service.recordSignal(signal, "EXECUTED", null, "trade-002");

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("DCA");
        }

        @Test
        @DisplayName("REJECTED 訊號 -> 記錄 rejectionReason")
        void rejectedSignal_recordsReason() {
            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("SOLUSDT")
                    .side(TradeSignal.Side.SHORT)
                    .isDca(false)
                    .build();

            when(deduplicationService.generateHash(signal)).thenReturn("hash-rej");

            service.recordSignal(signal, "REJECTED", "Symbol not in whitelist", null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            Signal saved = captor.getValue();

            assertThat(saved.getExecutionStatus()).isEqualTo("REJECTED");
            assertThat(saved.getRejectionReason()).isEqualTo("Symbol not in whitelist");
            assertThat(saved.getTradeId()).isNull();
        }

        @Test
        @DisplayName("null signal (解析失敗) -> 安全記錄 IGNORED")
        void nullSignal_safelyRecordsIgnored() {
            service.recordSignal(null, "IGNORED", "Parse failed", null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            Signal saved = captor.getValue();

            assertThat(saved.getSignalId()).isNotNull();
            assertThat(saved.getExecutionStatus()).isEqualTo("IGNORED");
            assertThat(saved.getRejectionReason()).isEqualTo("Parse failed");
            assertThat(saved.getSymbol()).isNull();
            assertThat(saved.getAction()).isNull();
        }

        @Test
        @DisplayName("SignalSource 完整記錄")
        void signalSource_recordedFully() {
            SignalSource source = SignalSource.builder()
                    .platform("discord")
                    .channelId("ch-123")
                    .channelName("vip-signals")
                    .guildId("guild-456")
                    .authorName("TraderJoe")
                    .messageId("msg-789")
                    .build();

            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(false)
                    .source(source)
                    .build();

            when(deduplicationService.generateHash(signal)).thenReturn("hash-src");

            service.recordSignal(signal, "EXECUTED", null, null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            Signal saved = captor.getValue();

            assertThat(saved.getSourcePlatform()).isEqualTo("discord");
            assertThat(saved.getSourceChannelId()).isEqualTo("ch-123");
            assertThat(saved.getSourceChannelName()).isEqualTo("vip-signals");
            assertThat(saved.getSourceGuildId()).isEqualTo("guild-456");
            assertThat(saved.getSourceAuthorName()).isEqualTo("TraderJoe");
            assertThat(saved.getSourceMessageId()).isEqualTo("msg-789");
        }

        @Test
        @DisplayName("DB 例外不傳播 (fire-and-forget)")
        void dbException_doesNotPropagate() {
            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(false)
                    .build();

            when(signalRepository.save(any())).thenThrow(new RuntimeException("DB connection lost"));
            when(deduplicationService.generateHash(any())).thenReturn("hash");

            // 不應拋出例外
            assertThatCode(() ->
                    service.recordSignal(signal, "EXECUTED", null, null)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Hash 產生失敗不影響記錄")
        void hashGenerationFails_stillRecords() {
            TradeSignal signal = TradeSignal.builder()
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(false)
                    .build();

            when(deduplicationService.generateHash(any()))
                    .thenThrow(new RuntimeException("Hash error"));

            service.recordSignal(signal, "EXECUTED", null, null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            // hash 為 null 但其他欄位正常
            assertThat(captor.getValue().getSignalHash()).isNull();
            assertThat(captor.getValue().getSymbol()).isEqualTo("BTCUSDT");
        }
    }

    @Nested
    @DisplayName("recordFromRequest")
    class RecordFromRequest {

        @Test
        @DisplayName("ENTRY request -> 建立 TradeSignal 並記錄")
        void entryRequest_createsAndRecords() {
            when(deduplicationService.generateHash(any())).thenReturn("hash-req");

            service.recordFromRequest(
                    "ENTRY", "BTCUSDT", "LONG",
                    60000.0, 58000.0,
                    "EXECUTED", null, "trade-req-1", null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            Signal saved = captor.getValue();

            assertThat(saved.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(saved.getSide()).isEqualTo("LONG");
            assertThat(saved.getEntryPriceLow()).isEqualTo(60000.0);
            assertThat(saved.getStopLoss()).isEqualTo(58000.0);
            assertThat(saved.getTradeId()).isEqualTo("trade-req-1");
        }

        @Test
        @DisplayName("CLOSE request -> action 為 CLOSE")
        void closeRequest_actionIsClose() {
            when(deduplicationService.generateHash(any())).thenReturn("hash-close");

            service.recordFromRequest(
                    "CLOSE", "ETHUSDT", "SHORT",
                    null, null,
                    "EXECUTED", null, null, null);

            ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
            verify(signalRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("CLOSE");
        }

        @Test
        @DisplayName("例外不傳播")
        void exception_doesNotPropagate() {
            when(signalRepository.save(any())).thenThrow(new RuntimeException("fail"));
            when(deduplicationService.generateHash(any())).thenReturn("hash");

            assertThatCode(() ->
                    service.recordFromRequest(
                            "ENTRY", "BTCUSDT", "LONG",
                            60000.0, 58000.0,
                            "EXECUTED", null, null, null)
            ).doesNotThrowAnyException();
        }
    }
}
