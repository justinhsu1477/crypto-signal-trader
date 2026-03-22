package com.trader.trading.model;

import com.trader.shared.model.TradeSignal;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MartingaleSession {

    public enum Status {
        IDLE,
        ACTIVE,
        EXITING
    }

    private final String sessionId;
    private final String symbol;
    private final TradeSignal.Side side;
    private final int plannedLayers;
    private final double baseEntryPrice;
    private final AtomicInteger filledLayers = new AtomicInteger(0);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile String currentTpOrderId;
    /** 階梯式 Trailing Stop 目前層級（0=未觸發，1~4=對應 Trailing 階段） */
    private volatile int trailingLevel;
    /** TP 時間衰減已套用的階段數（0=未衰減） */
    private volatile int tpDecayLevel;

    public MartingaleSession(String sessionId, String symbol, TradeSignal.Side side, int plannedLayers, double baseEntryPrice) {
        this.sessionId = sessionId;
        this.symbol = symbol;
        this.side = side;
        this.plannedLayers = plannedLayers;
        this.baseEntryPrice = baseEntryPrice;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSymbol() {
        return symbol;
    }

    public TradeSignal.Side getSide() {
        return side;
    }

    public int getPlannedLayers() {
        return plannedLayers;
    }

    public double getBaseEntryPrice() {
        return baseEntryPrice;
    }

    public int getFilledLayers() {
        return filledLayers.get();
    }

    public Status getStatus() {
        return status.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCurrentTpOrderId() {
        return currentTpOrderId;
    }

    public void setCurrentTpOrderId(String currentTpOrderId) {
        this.currentTpOrderId = currentTpOrderId;
        touch();
    }

    public void markFilledLayer() {
        filledLayers.incrementAndGet();
        touch();
    }

    public boolean isBreakevenActivated() {
        return trailingLevel > 0;
    }

    public int getTrailingLevel() {
        return trailingLevel;
    }

    public void setTrailingLevel(int level) {
        this.trailingLevel = level;
        touch();
    }

    public int getTpDecayLevel() {
        return tpDecayLevel;
    }

    public void setTpDecayLevel(int level) {
        this.tpDecayLevel = level;
        touch();
    }

    /** 向下相容：設 true = level 1，設 false = level 0 */
    public void setBreakevenActivated(boolean activated) {
        if (activated && trailingLevel == 0) {
            this.trailingLevel = 1;
        } else if (!activated) {
            this.trailingLevel = 0;
        }
        touch();
    }

    public void updateStatus(Status newStatus) {
        status.set(newStatus);
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
