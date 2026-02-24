package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 每日開盤餘額快取（Lazy Init）
 *
 * 每位用戶每日第一次查詢時快照餘額，當日後續查詢直接回傳快取值。
 * 跨日（Asia/Taipei 00:00）自動失效，下次查詢重新快照。
 *
 * 用途：daily-loss-percent 熔斷需要用「當日開盤餘額」計算上限，
 * 避免日內虧損導致餘額縮水、上限跟著縮、越虧越快觸發熔斷。
 */
@Slf4j
@Service
public class StartOfDayBalanceCache {

    private final ConcurrentHashMap<String, DayBalance> cache = new ConcurrentHashMap<>();

    private record DayBalance(LocalDate date, double balance) {}

    /**
     * 取得用戶的當日開盤餘額。
     * 若當日尚未快取，透過 balanceFetcher 取得即時餘額並快取。
     *
     * @param userId        用戶 ID
     * @param balanceFetcher 即時餘額取得函式（通常是 getAvailableBalance()）
     * @return 當日開盤餘額
     */
    public double getOrCompute(String userId, Supplier<Double> balanceFetcher) {
        LocalDate today = LocalDate.now(AppConstants.ZONE_ID);

        DayBalance existing = cache.get(userId);
        if (existing != null && existing.date().equals(today)) {
            return existing.balance();
        }

        // Lazy init：第一筆交易時快照
        double balance = balanceFetcher.get();
        cache.put(userId, new DayBalance(today, balance));
        log.info("SOD balance cached: userId={}, date={}, balance={}", userId, today, balance);

        // 清除其他用戶的過期快取
        cache.entrySet().removeIf(e -> !e.getValue().date().equals(today));

        return balance;
    }

    /** 測試用：清除指定用戶的快取 */
    public void evict(String userId) {
        cache.remove(userId);
    }

    /** 測試用：清除全部快取 */
    public void evictAll() {
        cache.clear();
    }
}
