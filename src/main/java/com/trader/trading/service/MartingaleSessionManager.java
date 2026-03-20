package com.trader.trading.service;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.MartingaleSession;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MartingaleSessionManager {

    private final ConcurrentHashMap<String, MartingaleSession> sessions = new ConcurrentHashMap<>();

    public Optional<MartingaleSession> getActiveSession(String symbol) {
        MartingaleSession session = sessions.get(symbol);
        if (session == null) {
            return Optional.empty();
        }
        if (session.getStatus() == MartingaleSession.Status.EXITING) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public MartingaleSession startSession(String symbol, TradeSignal.Side side, int plannedLayers) {
        MartingaleSession newSession = new MartingaleSession(UUID.randomUUID().toString(), symbol, side, plannedLayers);
        MartingaleSession existing = sessions.putIfAbsent(symbol, newSession);
        return existing != null ? existing : newSession;
    }

    public void markExiting(String symbol) {
        MartingaleSession session = sessions.get(symbol);
        if (session != null) {
            session.updateStatus(MartingaleSession.Status.EXITING);
        }
    }

    public void endSession(String symbol) {
        sessions.remove(symbol);
    }

    public Collection<MartingaleSession> getSessionsSnapshot() {
        return sessions.values();
    }
}
