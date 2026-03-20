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

    public void markFilledLayer() {
        filledLayers.incrementAndGet();
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
