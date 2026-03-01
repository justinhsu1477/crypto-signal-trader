package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重複訊號防護服務（兩層去重模型）
 *
 * === Signal-level（全局，廣播前） ===
 * 防止 Discord 重連/重發導致同一訊號被多次廣播。
 * 在 /api/broadcast-trade 入口處呼叫 {@link #isSignalProcessed}，一次檢查。
 *
 * === Execution-level（per-user，執行時） ===
 * 防止同一用戶重複執行同一訊號（例如手動重試）。
 * 在 executeSignalInternal() 呼叫 {@link #isUserDuplicate}，hash 包含 userId。
 * 不同用戶對同一訊號不會互相阻擋。
 *
 * 雙層防護策略（每層內部）:
 * 1. 內存快取 — ConcurrentHashMap 記錄 signalHash + 時間戳, 同一訊號在時間窗口內直接拒絕（毫秒級）
 * 2. DB 持久化 — Trade 表記錄 signalHash, 即使重啟後也能查到最近是否有相同訊號
 *
 * signalHash 生成: SHA256(symbol + "|" + side + "|" + entryPriceLow + "|" + stopLoss)
 * 只用核心交易參數，不用 rawMessage（因為空白、emoji 可能微變）
 */
@Slf4j
@Service
public class SignalDeduplicationService {

    private final TradeRepository tradeRepository;
    private final RiskConfig riskConfig;

    /**
     * 內存快取: signalHash → 首次收到的時間戳 (epoch millis)
     */
    private final ConcurrentHashMap<String, Long> recentSignals = new ConcurrentHashMap<>();

    /**
     * 時間窗口（毫秒）: 同一 signalHash 在這個窗口內的重複請求會被拒絕
     * 預設 5 分鐘 — 覆蓋大部分 CDP 重連、訊息編輯等重複場景
     */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000;

    /**
     * 內存快取清理門檻：超過此數量時清理過期的條目
     */
    private static final int CACHE_CLEANUP_THRESHOLD = 500;

    public SignalDeduplicationService(TradeRepository tradeRepository, RiskConfig riskConfig) {
        this.tradeRepository = tradeRepository;
        this.riskConfig = riskConfig;
    }

    /**
     * 檢查 ENTRY 訊號是否重複
     *
     * @param signal 已解析的交易訊號
     * @return true = 重複（應拒絕）, false = 非重複（可執行）
     */
    public boolean isDuplicate(TradeSignal signal) {
        if (!riskConfig.isDedupEnabled()) {
            log.debug("重複訊號防護已關閉 (dedup-enabled=false)");
            return false;
        }

        String hash = generateHash(signal);

        // ===== 第一層：內存快速檢查（原子操作，防 race condition）=====
        long now = System.currentTimeMillis();
        Long previousTime = recentSignals.putIfAbsent(hash, now);

        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            long elapsedSec = (now - previousTime) / 1000;
            log.warn("🔁 重複訊號攔截（內存）: hash={} 距上次 {}秒, 窗口={}秒",
                    hash.substring(0, 12), elapsedSec, DEDUP_WINDOW_MS / 1000);
            return true;
        }

        // putIfAbsent 返回 non-null 但已過期 → 更新時間戳
        if (previousTime != null) {
            recentSignals.put(hash, now);
        }

        // ===== 第二層：DB 持久化檢查 =====
        // 查詢最近 DEDUP_WINDOW 內是否有相同 signalHash 的 OPEN 或 CLOSED 交易
        LocalDateTime windowStart = LocalDateTime.now(AppConstants.ZONE_ID).minusSeconds(DEDUP_WINDOW_MS / 1000);
        boolean existsInDb = tradeRepository.existsBySignalHashAndCreatedAtAfter(hash, windowStart);

        if (existsInDb) {
            log.warn("🔁 重複訊號攔截（DB）: hash={} 在最近 {}分鐘內已有交易紀錄",
                    hash.substring(0, 12), DEDUP_WINDOW_MS / 1000 / 60);
            recentSignals.put(hash, now);
            return true;
        }

        cleanupIfNeeded();

        log.info("✅ 訊號去重通過: hash={} {} {} entry={} SL={}",
                hash.substring(0, 12), signal.getSymbol(), signal.getSide(),
                signal.getEntryPriceLow(), signal.getStopLoss());

        return false;
    }

    /**
     * 檢查 CANCEL 訊號是否重複（只用 symbol 判斷）
     *
     * @param symbol 交易對
     * @return true = 重複（應拒絕）
     */
    public boolean isCancelDuplicate(String symbol) {
        if (!riskConfig.isDedupEnabled()) {
            return false;
        }

        String hash = "CANCEL|" + symbol;
        long now = System.currentTimeMillis();

        // CANCEL 用較短的窗口: 30 秒（原子操作防 race condition）
        long cancelWindow = 30 * 1000;
        Long previousTime = recentSignals.putIfAbsent(hash, now);

        if (previousTime != null && (now - previousTime) < cancelWindow) {
            log.warn("🔁 重複取消攔截: {} 距上次 {}秒",
                    symbol, (now - previousTime) / 1000);
            return true;
        }

        // putIfAbsent 返回 non-null 但已過期 → 更新時間戳
        if (previousTime != null) {
            recentSignals.put(hash, now);
        }
        return false;
    }

    /**
     * 檢查 CANCEL 訊號是否重複（per-user，廣播用）
     *
     * 廣播取消時，每個用戶需獨立執行 cancel，
     * hash 包含 userId，不同用戶不會互相阻擋。
     *
     * @param symbol 交易對
     * @param userId 用戶 ID
     * @return true = 重複（應拒絕）
     */
    public boolean isCancelDuplicate(String symbol, String userId) {
        if (!riskConfig.isDedupEnabled()) {
            return false;
        }

        String hash = "CANCEL|" + userId + "|" + symbol;
        long now = System.currentTimeMillis();

        // CANCEL 用較短的窗口: 30 秒（原子操作防 race condition）
        long cancelWindow = 30 * 1000;
        Long previousTime = recentSignals.putIfAbsent(hash, now);

        if (previousTime != null && (now - previousTime) < cancelWindow) {
            log.warn("🔁 重複取消攔截（per-user）: userId={} {} 距上次 {}秒",
                    userId, symbol, (now - previousTime) / 1000);
            return true;
        }

        // putIfAbsent 返回 non-null 但已過期 → 更新時間戳
        if (previousTime != null) {
            recentSignals.put(hash, now);
        }
        return false;
    }

    /**
     * 生成訊號的去重 Hash
     * 使用核心交易參數: symbol + side + entryPriceLow + stopLoss
     */
    public String generateHash(TradeSignal signal) {
        // DCA 時 side 可能為 null（由 BinanceFuturesService 從持倉推斷），用 "DCA" 代替
        String sideStr = signal.getSide() != null ? signal.getSide().name() : "DCA";
        String raw = String.join("|",
                signal.getSymbol(),
                sideStr,
                String.valueOf(signal.getEntryPriceLow()),
                String.valueOf(signal.getStopLoss())
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 一定存在，不會發生
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Signal-level 去重（全局，廣播入口用） ====================

    /**
     * Signal-level 去重：檢查此訊號是否已被廣播處理過
     *
     * 用於 /api/broadcast-trade 入口（廣播前一次檢查），
     * 防止 Discord 重連/重發導致同一訊號被多次廣播給所有用戶。
     *
     * 邏輯與 {@link #isDuplicate} 相同（內存 + DB 雙層），
     * 但語義上是「訊號是否已進入過系統」而非「是否已被某用戶執行」。
     *
     * @param signal 已解析的交易訊號
     * @return true = 已處理（應跳過廣播）, false = 未處理（可廣播）
     */
    public boolean isSignalProcessed(TradeSignal signal) {
        return isDuplicate(signal);
    }

    // ==================== Execution-level 去重（per-user，執行時用） ====================

    /**
     * Execution-level 去重：檢查此用戶是否已執行過此訊號
     *
     * 用於 executeSignalInternal()，替代原本的 isDuplicate()。
     * hash 包含 userId，因此不同用戶對同一訊號不會互相阻擋。
     *
     * 只使用內存快取（不查 DB），因為：
     * 1. DB 中 Trade.signalHash 不含 userId（保持全局訊號追蹤語義）
     * 2. 內存快取足以覆蓋同一次廣播中的併發場景
     *
     * @param signal 已解析的交易訊號
     * @param userId 當前執行的用戶 ID
     * @return true = 此用戶已執行過（應拒絕）, false = 未執行（可繼續）
     */
    public boolean isUserDuplicate(TradeSignal signal, String userId) {
        if (!riskConfig.isDedupEnabled()) {
            log.debug("重複訊號防護已關閉 (dedup-enabled=false)");
            return false;
        }

        String userHash = generateUserHash(signal, userId);

        long now = System.currentTimeMillis();
        Long previousTime = recentSignals.putIfAbsent(userHash, now);

        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            long elapsedSec = (now - previousTime) / 1000;
            log.warn("🔁 重複訊號攔截（per-user）: userId={} hash={} 距上次 {}秒",
                    userId, userHash.substring(0, 12), elapsedSec);
            return true;
        }

        // putIfAbsent 返回 non-null 但已過期 → 更新時間戳
        if (previousTime != null) {
            recentSignals.put(userHash, now);
        }

        cleanupIfNeeded();

        log.info("✅ 用戶去重通過: userId={} hash={} {} {} entry={} SL={}",
                userId, userHash.substring(0, 12), signal.getSymbol(), signal.getSide(),
                signal.getEntryPriceLow(), signal.getStopLoss());

        return false;
    }

    /**
     * 生成 per-user 的去重 Hash
     * 在原有訊號 hash 前加入 userId，確保不同用戶不會互相阻擋
     */
    public String generateUserHash(TradeSignal signal, String userId) {
        String sideStr = signal.getSide() != null ? signal.getSide().name() : "DCA";
        String raw = String.join("|",
                userId,
                signal.getSymbol(),
                sideStr,
                String.valueOf(signal.getEntryPriceLow()),
                String.valueOf(signal.getStopLoss())
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 清理過期的內存快取條目
     */
    private void cleanupIfNeeded() {
        if (recentSignals.size() > CACHE_CLEANUP_THRESHOLD) {
            long now = System.currentTimeMillis();
            int removed = 0;
            var it = recentSignals.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if ((now - entry.getValue()) > DEDUP_WINDOW_MS) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                log.debug("清理過期去重快取: 移除 {} 條, 剩餘 {}", removed, recentSignals.size());
            }
        }
    }
}
