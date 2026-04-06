package com.trader.trading.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨服務的 Per-symbol 互斥鎖註冊表
 *
 * 問題背景：BinanceFuturesService 和 BinanceUserDataStreamService
 * 都會操作同一個 symbol 的倉位/紀錄。如果各自持有獨立的 lock map，
 * 兩邊可以同時操作同一幣種，導致競態條件（race condition）。
 *
 * 解法：抽出共享的 @Component，所有 Service 注入同一個 bean，
 * 確保 getSymbolLock("BTCUSDT") 全域唯一。
 */
@Component
public class SymbolLockRegistry {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 取得指定 symbol 的互斥鎖（不存在則建立）
     * 同一個 symbol 全域只有一把鎖，跨 Service 共享。
     */
    public ReentrantLock getLock(String symbol) {
        return locks.computeIfAbsent(symbol, k -> new ReentrantLock());
    }

    /**
     * 移除不再使用的 lock（Session 結束後呼叫，防止記憶體洩漏）。
     * 只在 lock 未被持有時才移除，避免影響正在使用的 lock。
     */
    public void removeLockIfIdle(String symbol) {
        locks.computeIfPresent(symbol, (k, lock) -> lock.isLocked() ? lock : null);
    }
}
