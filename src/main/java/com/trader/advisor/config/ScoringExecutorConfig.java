package com.trader.advisor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * AI 信號評分專用線程池
 *
 * 設計理念：評分是 IO-bound（等 Gemini 2-5 秒），用少量線程 + bounded queue。
 * - coreSize=2：平時保持 2 條線程（Gemini 單次 2-5s，2 條足夠消化一般頻率）
 * - maxSize=4：突發時最多 4 條同時打 Gemini
 * - queue=LinkedBlockingQueue(8)：最多排 8 個待評分，超過直接拒絕
 * - AbortPolicy：滿了拋 RejectedExecutionException，由 SignalScoringService 捕獲後回傳 null
 * - thread-name: "scoring-0", "scoring-1"... 方便 thread dump 辨識
 *
 * 隔離性：跟 broadcastExecutor（下單用）和 ForkJoinPool.commonPool 完全獨立。
 * 即使 Gemini API 全部卡住，也只影響這 4 條線程，不拖累主流程。
 */
@Slf4j
@Configuration
public class ScoringExecutorConfig {

    private ThreadPoolExecutor scoringExecutor;

    @Bean(name = "scoringExecutor")
    public ThreadPoolExecutor scoringExecutor() {
        this.scoringExecutor = new ThreadPoolExecutor(
                2,                                  // corePoolSize：平時保持
                4,                                  // maxPoolSize：突發上限
                60L, TimeUnit.SECONDS,              // keepAliveTime：閒置回收
                new LinkedBlockingQueue<>(8),        // 有界隊列：最多排 8 個
                new ScoringThreadFactory(),          // 自訂線程名稱
                new ThreadPoolExecutor.AbortPolicy() // 滿了拋異常，由呼叫端降級
        );
        log.info("AI 評分線程池已初始化: core=2, max=4, queue=8, policy=AbortPolicy");
        return this.scoringExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (scoringExecutor != null) {
            log.info("正在關閉 AI 評分線程池...");
            scoringExecutor.shutdown();
            try {
                if (!scoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("AI 評分線程池未在 5 秒內關閉，強制終止");
                    scoringExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scoringExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("AI 評分線程池已關閉");
        }
    }

    /**
     * 自訂線程工廠：命名為 scoring-0, scoring-1...
     * 方便 thread dump 和 log 辨識
     */
    private static class ScoringThreadFactory implements ThreadFactory {
        private int count = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "scoring-" + count++);
            t.setDaemon(true);  // daemon 線程，不阻擋 JVM 關閉
            return t;
        }
    }
}
