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

    public MartingaleSession startSession(String symbol, TradeSignal.Side side, int plannedLayers, double baseEntryPrice) {
        if (baseEntryPrice <= 0) {
            throw new IllegalArgumentException("baseEntryPrice must be > 0, got: " + baseEntryPrice);
        }
        MartingaleSession newSession = new MartingaleSession(UUID.randomUUID().toString(), symbol, side, plannedLayers, baseEntryPrice);
        // 用 compute 取代 putIfAbsent：如果舊 session 是 EXITING 殭屍，直接覆蓋
        return sessions.compute(symbol, (k, existing) -> {
            if (existing == null || existing.getStatus() == MartingaleSession.Status.EXITING) {
                return newSession;
            }
            return existing;
        });
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

    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.getStatus() == MartingaleSession.Status.ACTIVE)
                .count();
    }

    public Collection<MartingaleSession> getSessionsSnapshot() {
        return sessions.values();
    }
}
