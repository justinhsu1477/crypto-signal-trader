package com.trader.trading.service.martingale;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.MartingaleSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Martingale 狀態持久化：將 Session 和 LayerFill 寫入 Redis，重啟後自動恢復。
 * 每次狀態變更由呼叫端主動呼叫 persist 方法，避免侵入核心邏輯。
 */
@Slf4j
@Component
public class MartingaleStateStore {

    private static final String SESSION_KEY_PREFIX = "martingale:session:";
    private static final String FILL_KEY_PREFIX = "martingale:fill:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;

    public MartingaleStateStore(StringRedisTemplate redisTemplate,
                                MartingaleSessionManager sessionManager,
                                LayerFillTracker layerFillTracker) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
    }

    // ========== Persist ==========

    public void persistSession(MartingaleSession session) {
        if (session == null || session.getSymbol() == null) return;
        try {
            SessionSnapshot snap = SessionSnapshot.from(session);
            String json = objectMapper.writeValueAsString(snap);
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + session.getSymbol(), json);
        } catch (Exception e) {
            log.warn("Persist session failed: symbol={} err={}", session.getSymbol(), e.getMessage());
        }
    }

    public void persistFill(String symbol) {
        if (symbol == null) return;
        try {
            LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(symbol);
            FillSnapshot snap = new FillSnapshot(fill.totalQty(), fill.avgPrice());
            String json = objectMapper.writeValueAsString(snap);
            redisTemplate.opsForValue().set(FILL_KEY_PREFIX + symbol, json);
        } catch (Exception e) {
            log.warn("Persist fill failed: symbol={} err={}", symbol, e.getMessage());
        }
    }

    public void removeSession(String symbol) {
        if (symbol == null) return;
        try {
            redisTemplate.delete(SESSION_KEY_PREFIX + symbol);
            redisTemplate.delete(FILL_KEY_PREFIX + symbol);
        } catch (Exception e) {
            log.warn("Remove session state failed: symbol={} err={}", symbol, e.getMessage());
        }
    }

    // ========== Restore on startup ==========

    @EventListener(ApplicationReadyEvent.class)
    public void restoreFromRedis() {
        try {
            var keys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                log.info("MartingaleStateStore: 無 Redis 快照需恢復");
                return;
            }

            int restored = 0;
            for (String key : keys) {
                String symbol = key.substring(SESSION_KEY_PREFIX.length());
                try {
                    // 恢復 session
                    String sessionJson = redisTemplate.opsForValue().get(key);
                    if (sessionJson == null) continue;

                    SessionSnapshot snap = objectMapper.readValue(sessionJson, SessionSnapshot.class);
                    if (snap.status == null || snap.status == MartingaleSession.Status.EXITING) {
                        // 已結束的 session 不恢復
                        redisTemplate.delete(key);
                        redisTemplate.delete(FILL_KEY_PREFIX + symbol);
                        continue;
                    }

                    // 檢查是否已被 RecoveryTask 恢復
                    if (sessionManager.getActiveSession(symbol).isPresent()) {
                        log.debug("MartingaleStateStore: {} 已有 active session，跳過 Redis 恢復", symbol);
                        continue;
                    }

                    MartingaleSession session = sessionManager.startSession(
                            symbol, snap.side, snap.plannedLayers, snap.baseEntryPrice);

                    // 恢復 filledLayers
                    for (int i = 0; i < snap.filledLayers; i++) {
                        session.markFilledLayer();
                    }
                    session.setTrailingLevel(snap.trailingLevel);
                    session.setTpDecayLevel(snap.tpDecayLevel);
                    if (snap.tpOrderId != null) {
                        session.setCurrentTpOrderId(snap.tpOrderId);
                    }

                    // 恢復 fill tracker（用聚合快照寫回）
                    String fillJson = redisTemplate.opsForValue().get(FILL_KEY_PREFIX + symbol);
                    if (fillJson != null) {
                        FillSnapshot fillSnap = objectMapper.readValue(fillJson, FillSnapshot.class);
                        if (fillSnap.totalQty > 0 && fillSnap.avgPrice > 0) {
                            layerFillTracker.recordFillDirect(symbol, fillSnap.totalQty, fillSnap.avgPrice);
                        }
                    }

                    restored++;
                    log.info("MartingaleStateStore: 從 Redis 恢復 session symbol={} side={} filled={}/{}",
                            symbol, snap.side, snap.filledLayers, snap.plannedLayers);
                } catch (Exception e) {
                    log.warn("MartingaleStateStore: 恢復 {} 失敗: {}", symbol, e.getMessage());
                }
            }
            log.info("MartingaleStateStore: 共恢復 {} 個 session", restored);
        } catch (Exception e) {
            log.error("MartingaleStateStore: Redis 恢復失敗: {}", e.getMessage(), e);
        }
    }

    // ========== Snapshot DTOs ==========

    public static class SessionSnapshot {
        public String symbol;
        public TradeSignal.Side side;
        public int plannedLayers;
        public double baseEntryPrice;
        public int filledLayers;
        public MartingaleSession.Status status;
        public int trailingLevel;
        public int tpDecayLevel;
        public String tpOrderId;

        public SessionSnapshot() {} // for Jackson

        public static SessionSnapshot from(MartingaleSession s) {
            SessionSnapshot snap = new SessionSnapshot();
            snap.symbol = s.getSymbol();
            snap.side = s.getSide();
            snap.plannedLayers = s.getPlannedLayers();
            snap.baseEntryPrice = s.getBaseEntryPrice();
            snap.filledLayers = s.getFilledLayers();
            snap.status = s.getStatus();
            snap.trailingLevel = s.getTrailingLevel();
            snap.tpDecayLevel = s.getTpDecayLevel();
            snap.tpOrderId = s.getCurrentTpOrderId();
            return snap;
        }
    }

    public static class FillSnapshot {
        public double totalQty;
        public double avgPrice;

        public FillSnapshot() {} // for Jackson
        public FillSnapshot(double totalQty, double avgPrice) {
            this.totalQty = totalQty;
            this.avgPrice = avgPrice;
        }
    }
}
