package com.trader.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@code @Async} 設定的單一入口。
 *
 * <p>過去這個專案有 {@link org.springframework.scheduling.annotation.EnableAsync} 但沒寫
 * {@code AsyncConfigurer} — 所有 {@code @Async} method 跑在 Spring 預設的
 * {@code SimpleAsyncTaskExecutor}（每次 new thread、無上限、無命名）。
 *
 * <p>本 config 接通兩件事：
 * <ol>
 *   <li>提供 {@code auditExecutor}（有界線程池）作為預設執行器</li>
 *   <li>提供統一的 uncaught exception handler，避免 void {@code @Async} 拋例外被吞掉</li>
 * </ol>
 *
 * <p>注意：業務關鍵路徑（{@code broadcastExecutor} / {@code scoringExecutor}）仍走顯式
 * {@code ExecutorService.submit()}，不經過 {@code @Async}。本 config 只服務 audit 等
 * fire-and-forget 邊角路徑。
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Audit / log fire-and-forget 用的有界線程池。
     *
     * <p>跟 broadcastExecutor / scoringExecutor 隔離，避免互搶資源：
     * <ul>
     *   <li>core=1 / max=2：audit 量低（每天幾百筆 DB write），不需要更多</li>
     *   <li>queue=200：吸收瞬間 burst</li>
     *   <li>滿了 → DiscardOldestPolicy：寧可丟舊的稽核，絕不擋業務</li>
     *   <li>命名 {@code audit-N}：log stack trace 一眼分辨來源</li>
     * </ul>
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("audit-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }

    /**
     * 沒有 qualifier 的 {@code @Async} 預設走 auditExecutor。
     *
     * <p>透過 CGLIB 攔截 @Configuration class 的 @Bean method call，
     * 回傳 cache 過的 bean 實例，不會 new 第二份。
     */
    @Override
    public Executor getAsyncExecutor() {
        return auditExecutor();
    }

    /**
     * void {@code @Async} method 拋例外時的兜底處理 —
     * 預設 Spring 行為是 silently 吞掉，這裡至少記到 log，方便事後排查。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Async {}.{} uncaught exception: {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        ex.getMessage(), ex);
    }
}
