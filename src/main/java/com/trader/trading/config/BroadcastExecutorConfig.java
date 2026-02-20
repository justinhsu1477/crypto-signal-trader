package com.trader.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * 廣播跟單線程池配置（共享單例）
 *
 * 設計理念：跟單場景需要「全員同時下單」，不適合排隊削峰。
 * - SynchronousQueue：任務來了直接開線程，不排隊
 * - coreSize=10：平時保持 10 條線程
 * - maxSize=50：突發時最多 50 條（覆蓋初期用戶量）
 * - keepAlive=60s：多餘線程閒置 60 秒自動回收
 * - CallerRunsPolicy：超過 maxSize 時由呼叫者線程自己做（降級，不丟棄）
 */
@Slf4j
@Configuration
public class BroadcastExecutorConfig {

    private ExecutorService broadcastExecutor;

    @Bean(name = "broadcastExecutor")
    public ExecutorService broadcastExecutor() {
        this.broadcastExecutor = new ThreadPoolExecutor(
                10,                             // corePoolSize：平時保持
                50,                             // maxPoolSize：突發上限
                60L, TimeUnit.SECONDS,          // keepAliveTime：閒置回收
                new SynchronousQueue<>(),        // 不排隊，直接開線程
                new ThreadPoolExecutor.CallerRunsPolicy()  // 超過 max → 呼叫者自己做
        );
        log.info("廣播跟單線程池已初始化: core=10, max=50, keepAlive=60s, queue=SynchronousQueue");
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
