package com.trader.trading.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.entity.Signal;
import com.trader.trading.repository.SignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 訊號紀錄服務 — Fire-and-forget
 *
 * 每個進入系統的訊號（不論執行結果）都記錄到 signals 表。
 * 全包 try-catch，永遠不阻塞交易流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalRecordService {

    private final SignalRepository signalRepository;
    private final SignalDeduplicationService deduplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 記錄訊號到 DB
     *
     * @param signal          解析後的 TradeSignal（可為 null，表示解析失敗）
     * @param executionStatus EXECUTED / REJECTED / IGNORED / FAILED
     * @param rejectionReason 拒絕原因（nullable）
     * @param tradeId         關聯的交易 ID（nullable）
     */
    public void recordSignal(TradeSignal signal, String executionStatus,
                             String rejectionReason, String tradeId) {
        try {
            Signal.SignalBuilder builder = Signal.builder()
                    .signalId(UUID.randomUUID().toString())
                    .executionStatus(executionStatus)
                    .rejectionReason(rejectionReason)
                    .tradeId(tradeId);

            if (signal != null) {
                // 訊號內容
                builder.action(signal.isDca() ? "DCA" :
                               (signal.getSignalType() != null ? signal.getSignalType().name() : null))
                       .symbol(signal.getSymbol())
                       .side(signal.getSide() != null ? signal.getSide().name() : null)
                       .entryPriceLow(signal.getEntryPriceLow())
                       .entryPriceHigh(signal.getEntryPriceHigh())
                       .stopLoss(signal.getStopLoss())
                       .takeProfits(serializeTakeProfits(signal))
                       .leverage(signal.getLeverage())
                       .closeRatio(signal.getCloseRatio())
                       .newStopLoss(signal.getNewStopLoss())
                       .newTakeProfit(signal.getNewTakeProfit())
                       .rawMessage(signal.getRawMessage());

                // 去重 Hash
                try {
                    builder.signalHash(deduplicationService.generateHash(signal));
                } catch (Exception e) {
                    log.debug("Signal hash generation failed: {}", e.getMessage());
                }

                // 來源
                SignalSource source = signal.getSource();
                if (source != null) {
                    builder.sourcePlatform(source.getPlatform())
                           .sourceChannelId(source.getChannelId())
                           .sourceChannelName(source.getChannelName())
                           .sourceGuildId(source.getGuildId())
                           .sourceAuthorName(source.getAuthorName())
                           .sourceMessageId(source.getMessageId());
                }
            }

            Signal entity = builder.build();
            signalRepository.save(entity);
            log.debug("Signal recorded: id={} action={} symbol={} status={}",
                    entity.getSignalId(),
                    entity.getAction(),
                    entity.getSymbol(),
                    executionStatus);

        } catch (Exception e) {
            log.error("Signal recording failed (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * 從 TradeRequest 建立簡易 TradeSignal 並記錄
     * 用於 /api/execute-trade 和 /api/broadcast-trade
     */
    public void recordFromRequest(String action, String symbol, String side,
                                  Double entryPrice, Double stopLoss,
                                  String executionStatus, String rejectionReason,
                                  String tradeId, SignalSource source) {
        try {
            TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                    .signalType(parseSignalType(action))
                    .symbol(symbol)
                    .entryPriceLow(entryPrice != null ? entryPrice : 0)
                    .entryPriceHigh(entryPrice != null ? entryPrice : 0)
                    .stopLoss(stopLoss != null ? stopLoss : 0)
                    .isDca("DCA".equalsIgnoreCase(action))
                    .source(source);

            if (side != null) {
                try {
                    builder.side(TradeSignal.Side.valueOf(side.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }

            recordSignal(builder.build(), executionStatus, rejectionReason, tradeId);
        } catch (Exception e) {
            log.error("Signal recording from request failed (non-blocking): {}", e.getMessage());
        }
    }

    private String serializeTakeProfits(TradeSignal signal) {
        if (signal.getTakeProfits() == null || signal.getTakeProfits().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(signal.getTakeProfits());
        } catch (JsonProcessingException e) {
            return signal.getTakeProfits().toString();
        }
    }

    private TradeSignal.SignalType parseSignalType(String action) {
        if (action == null) return TradeSignal.SignalType.ENTRY;
        try {
            return TradeSignal.SignalType.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TradeSignal.SignalType.ENTRY;
        }
    }
}
