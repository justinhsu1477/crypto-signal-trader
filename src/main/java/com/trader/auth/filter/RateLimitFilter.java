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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP-based Rate Limiting Filter
 *
 * 保護 /api/auth/** 端點免受暴力破解：
 * - 登入/註冊：每個 IP 每分鐘最多 10 次
 * - 超過限制回傳 429 Too Many Requests
 * - 每分鐘自動重置計數器（避免記憶體無限增長）
 *
 * 放在 Security Filter 之前，被拒的請求不會進入認證流程。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)  // 在 CORS 之後、Security 之前
public class RateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final long WINDOW_MS = 60_000;  // 1 分鐘

    /** IP → 計數器 */
    private final ConcurrentHashMap<String, RateEntry> ipCounters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // 只限制 auth 端點
        if (!path.startsWith("/api/auth/")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(httpRequest);
        RateEntry entry = ipCounters.compute(clientIp, (ip, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                // 新窗口
                return new RateEntry(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (entry.count.get() > MAX_REQUESTS_PER_MINUTE) {
            log.warn("Rate limit exceeded: {} → {} requests/min on {}", clientIp, entry.count.get(), path);
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
     * 定期清理過期的 IP 記錄（避免記憶體洩漏）
     * 每 5 分鐘自動清一次
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        ipCounters.entrySet().removeIf(e -> now - e.getValue().windowStart > WINDOW_MS * 2);
    }

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

    private static class RateEntry {
        final long windowStart;
        final AtomicInteger count;

        RateEntry(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
