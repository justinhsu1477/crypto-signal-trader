package com.trader.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多路徑 IP-based Rate Limiting Filter
 *
 * 依照不同 API 端點分別設定限流上限（每分鐘）：
 * <ul>
 *   <li>/api/auth/**           → 10/min（防暴力破解）</li>
 *   <li>/api/execute-signal    → 30/min（交易訊號）</li>
 *   <li>/api/execute-trade     → 30/min（交易執行）</li>
 *   <li>/api/broadcast-trade   → 10/min（廣播跟單）</li>
 *   <li>/api/dashboard/**      → 60/min（前端查詢）</li>
 * </ul>
 *
 * 不在列表中的路徑不限流（如 /api/health、/api/monitor/**）。
 *
 * Rate key = IP + 路徑分組（同一組端點共享計數）。
 * 放在 Security Filter 之前，被拒的請求不會進入認證流程。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)  // 在 CORS 之後、Security 之前
public class RateLimitFilter implements Filter {

    private static final long WINDOW_MS = 60_000;  // 1 分鐘

    /** 限流規則：path prefix → (group name, max requests per minute) */
    static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule("/api/auth/",           "auth",      10),
            new RateLimitRule("/api/execute-signal",   "trade",     30),
            new RateLimitRule("/api/execute-trade",    "trade",     30),
            new RateLimitRule("/api/broadcast-trade",  "broadcast", 10),
            new RateLimitRule("/api/dashboard/",       "dashboard", 60)
    );

    /** key = "IP:group" → 計數器 */
    private final ConcurrentHashMap<String, RateEntry> counters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // 找出匹配的限流規則
        RateLimitRule matched = matchRule(path);
        if (matched == null) {
            // 不在限流範圍 — 直接放行
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(httpRequest);
        String key = clientIp + ":" + matched.group;

        RateEntry entry = counters.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                return new RateEntry(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (entry.count.get() > matched.maxPerMinute) {
            log.warn("Rate limit exceeded: {} → {}/{} requests/min on {} (group: {})",
                    clientIp, entry.count.get(), matched.maxPerMinute, path, matched.group);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    "{\"error\":\"請求過於頻繁，請稍後再試\",\"retryAfterSeconds\":60}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 定期清理過期的計數器（避免記憶體洩漏）
     * 每 5 分鐘自動清一次
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> now - e.getValue().windowStart > WINDOW_MS * 2);
    }

    // ========== package-private for testing ==========

    RateLimitRule matchRule(String path) {
        for (RateLimitRule rule : RULES) {
            if (path.startsWith(rule.pathPrefix)) {
                return rule;
            }
        }
        return null;
    }

    int getCounterSize() {
        return counters.size();
    }

    // ========== internal ==========

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    static class RateEntry {
        final long windowStart;
        final AtomicInteger count;

        RateEntry(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }

    static class RateLimitRule {
        final String pathPrefix;
        final String group;
        final int maxPerMinute;

        RateLimitRule(String pathPrefix, String group, int maxPerMinute) {
            this.pathPrefix = pathPrefix;
            this.group = group;
            this.maxPerMinute = maxPerMinute;
        }
    }
}
