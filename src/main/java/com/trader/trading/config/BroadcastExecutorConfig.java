package com.trader.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * 廣播跟單線程池配置（共享單例）
 *
 * 設計理念：跟單場景需要「全員同時下單」，不適合排隊削峰。
 * - SynchronousQueue：任務來了直接開線程，不排隊
 * - coreSize=5：平時保持 5 條線程
 * - maxSize=預設 20（對齊 HikariCP max-pool-size，避免 DB 連線飢餓）
 * - keepAlive=60s：多餘線程閒置 60 秒自動回收
 * - CallerRunsPolicy：超過 maxSize 時由呼叫者線程自己做（降級，不丟棄）
 *
 * 面試重點：
 *   - 線程池 maxSize 不得超過 DB 連接池 maxSize，否則會出現連線飢餓
 *     （50 threads 搶 20 connections → 30 threads 等到 connection-timeout → 交易失敗）
 *   - 兩者都透過環境變數配置，部署時一起調整
 *   - 為什麼不用 MQ？因為 broadcastTrade 需要 invokeAll() 同步等待全員結果做聚合報告，
 *     MQ fire-and-forget 無法做到即時結果收集
 */
@Slf4j
@Configuration
public class BroadcastExecutorConfig {

    @Value("${broadcast.executor.max-pool-size:${HIKARI_MAX_POOL_SIZE:20}}")
    private int maxPoolSize;

    private ExecutorService broadcastExecutor;

    @Bean(name = "broadcastExecutor")
    public ExecutorService broadcastExecutor() {
        int coreSize = Math.max(5, maxPoolSize / 4);
        this.broadcastExecutor = new ThreadPoolExecutor(
                coreSize,                       // corePoolSize：平時保持（maxPoolSize 的 1/4）
                maxPoolSize,                    // maxPoolSize：對齊 DB 連接池，避免連線飢餓
                60L, TimeUnit.SECONDS,          // keepAliveTime：閒置回收
                new SynchronousQueue<>(),        // 不排隊，直接開線程
                new ThreadPoolExecutor.CallerRunsPolicy()  // 超過 max → 呼叫者自己做
        );
        log.info("廣播跟單線程池已初始化: core={}, max={}, keepAlive=60s, queue=SynchronousQueue", coreSize, maxPoolSize);
        return this.broadcastExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (broadcastExecutor != null) {
            log.info("正在關閉廣播跟單線程池...");
            broadcastExecutor.shutdown();
            try {
                if (!broadcastExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("線程池未在 10 秒內關閉，強制終止");
                    broadcastExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                broadcastExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("廣播跟單線程池已關閉");
        }
    }
}
