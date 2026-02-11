package com.trader.service;

import com.trader.model.TradeSignal;
import com.trader.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重複訊號防護服務
 *
 * 雙層防護策略:
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

    public SignalDeduplicationService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    /**
     * 檢查 ENTRY 訊號是否重複
     *
     * @param signal 已解析的交易訊號
     * @return true = 重複（應拒絕）, false = 非重複（可執行）
     */
    public boolean isDuplicate(TradeSignal signal) {
        String hash = generateHash(signal);

        // ===== 第一層：內存快速檢查 =====
        Long previousTime = recentSignals.get(hash);
        long now = System.currentTimeMillis();

        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            long elapsedSec = (now - previousTime) / 1000;
            log.warn("🔁 重複訊號攔截（內存）: hash={} 距上次 {}秒, 窗口={}秒",
                    hash.substring(0, 12), elapsedSec, DEDUP_WINDOW_MS / 1000);
            return true;
        }

        // ===== 第二層：DB 持久化檢查 =====
        // 查詢最近 DEDUP_WINDOW 內是否有相同 signalHash 的 OPEN 或 CLOSED 交易
        LocalDateTime windowStart = LocalDateTime.now().minusSeconds(DEDUP_WINDOW_MS / 1000);
        boolean existsInDb = tradeRepository.existsBySignalHashAndCreatedAtAfter(hash, windowStart);

        if (existsInDb) {
            log.warn("🔁 重複訊號攔截（DB）: hash={} 在最近 {}分鐘內已有交易紀錄",
                    hash.substring(0, 12), DEDUP_WINDOW_MS / 1000 / 60);
            // 同步到內存快取
            recentSignals.put(hash, now);
            return true;
        }

        // ===== 非重複：登記到內存快取 =====
        recentSignals.put(hash, now);
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
        String hash = "CANCEL|" + symbol;
        Long previousTime = recentSignals.get(hash);
        long now = System.currentTimeMillis();

        // CANCEL 用較短的窗口: 30 秒
        long cancelWindow = 30 * 1000;
        if (previousTime != null && (now - previousTime) < cancelWindow) {
            log.warn("🔁 重複取消攔截: {} 距上次 {}秒",
                    symbol, (now - previousTime) / 1000);
            return true;
        }

        recentSignals.put(hash, now);
        return false;
    }

    /**
     * 生成訊號的去重 Hash
     * 使用核心交易參數: symbol + side + entryPriceLow + stopLoss
     */
    public String generateHash(TradeSignal signal) {
        String raw = String.join("|",
                signal.getSymbol(),
                signal.getSide().name(),
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
