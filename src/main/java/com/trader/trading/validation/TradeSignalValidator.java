package com.trader.trading.validation;

import com.trader.shared.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 交易訊號驗證器 — 在 executeSignal() 取得 symbol lock 之前呼叫。
 * 快速失敗：攔截 null/負數/除以零等結構性問題，避免 NPE 或無效 Binance API 呼叫。
 *
 * executeSignalInternal() 內的既有業務驗證（白名單、價格偏離、SL 方向）保留作為防禦深度。
 */
@Slf4j
@Service
public class TradeSignalValidator {

    /**
     * 驗證 TradeSignal 的結構完整性。
     *
     * @param signal 待驗證的訊號
     * @return Optional.empty() 表示通過；Optional 含錯誤訊息表示驗證失敗
     */
    public Optional<String> validate(TradeSignal signal) {
        // ── 基礎 null check ──
        if (signal == null) {
            return fail("TradeSignal 不可為 null");
        }
        if (signal.getSymbol() == null || signal.getSymbol().isBlank()) {
            return fail("symbol 不可為空");
        }
        if (signal.getSignalType() == null) {
            return fail("signalType 不可為 null");
        }

        // ── 依 signalType 分派驗證 ──
        return switch (signal.getSignalType()) {
            case ENTRY -> validateEntry(signal);
            case CLOSE -> validateClose(signal);
            case MOVE_SL -> validateMoveSL(signal);
            case CANCEL, INFO -> Optional.empty(); // 無需額外驗證
        };
    }

    /**
     * ENTRY 訊號驗證：確保入場價、止損、止盈的基本合理性。
     */
    private Optional<String> validateEntry(TradeSignal signal) {
        // side 必填（DCA 可從持倉推斷，允許 null）
        if (!signal.isDca() && signal.getSide() == null) {
            return fail("ENTRY 訊號必須包含 side (LONG/SHORT)");
        }

        // 入場價必須 > 0
        if (signal.getEntryPriceLow() <= 0) {
            return fail("入場價必須大於 0");
        }

        // 入場價上限（若有指定）必須 >= 下限
        if (signal.getEntryPriceHigh() > 0 && signal.getEntryPriceHigh() < signal.getEntryPriceLow()) {
            return fail("入場價下限不可大於上限");
        }

        // 止損不可為負數
        if (signal.getStopLoss() < 0) {
            return fail("止損價不可為負數");
        }

        // 入場價 == 止損 → 除以零風險（riskDistance = |entry - sl| = 0 → quantity = ∞）
        // DCA 允許 stopLoss=0（從 DB 查詢或使用預設 5% offset）
        if (!signal.isDca() && signal.getStopLoss() > 0
                && Double.compare(signal.getEntryPriceLow(), signal.getStopLoss()) == 0) {
            return fail("入場價不可等於止損價（會導致除以零）");
        }

        // 止盈目標不可為負數或零
        if (signal.getTakeProfits() != null) {
            for (int i = 0; i < signal.getTakeProfits().size(); i++) {
                Double tp = signal.getTakeProfits().get(i);
                if (tp == null || tp <= 0) {
                    return fail("止盈價必須大於 0（第 " + (i + 1) + " 個目標無效）");
                }
            }
        }

        return Optional.empty();
    }

    /**
     * CLOSE 訊號驗證：平倉比例必須在合理範圍。
     */
    private Optional<String> validateClose(TradeSignal signal) {
        if (signal.getCloseRatio() != null) {
            double ratio = signal.getCloseRatio();
            if (ratio <= 0 || ratio > 1.0) {
                return fail("平倉比例必須在 0-1 之間（收到: " + ratio + "）");
            }
        }
        return Optional.empty();
    }

    /**
     * MOVE_SL 訊號驗證：至少要有新止損或新止盈。
     */
    private Optional<String> validateMoveSL(TradeSignal signal) {
        boolean hasNewSL = signal.getNewStopLoss() != null;
        boolean hasNewTP = signal.getNewTakeProfit() != null;
        boolean hasTakeProfits = signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty();

        if (!hasNewSL && !hasNewTP && !hasTakeProfits) {
            return fail("MOVE_SL 必須提供新止損或新止盈");
        }

        // 新止損/止盈若有指定，必須 > 0
        if (hasNewSL && signal.getNewStopLoss() <= 0) {
            return fail("新止損價必須大於 0");
        }
        if (hasNewTP && signal.getNewTakeProfit() <= 0) {
            return fail("新止盈價必須大於 0");
        }

        return Optional.empty();
    }

    private Optional<String> fail(String message) {
        return Optional.of(message);
    }
}
