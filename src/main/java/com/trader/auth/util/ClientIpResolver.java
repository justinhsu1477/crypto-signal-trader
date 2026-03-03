package com.trader.auth.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 共用 Client IP 解析工具
 *
 * <pre>
 * 解決問題：
 *   Caddy 反向代理（Docker 容器）→ Spring Boot 看到的 remoteAddr 是 Docker IP（172.x.x.x）
 *   若 trusted-proxies 未包含 Docker IP，全站用戶共用同一個限流桶
 *
 * IP 解析優先順序（remoteAddr 為 trusted proxy 時）：
 *   1. security.ip-header 指定的 header（如 CF-Connecting-IP）
 *   2. X-Forwarded-For 第一個 IP
 *   3. X-Real-IP
 *   4. remoteAddr（fallback）
 *
 * remoteAddr 不是 trusted proxy → 直接回傳 remoteAddr（防 header 偽造）
 *
 * 支援 CIDR 格式（如 172.16.0.0/12）和精確 IP 比對。
 * </pre>
 */
@Slf4j
@Component
public class ClientIpResolver {

    @Value("${security.ip-header:}")
    private String ipHeader;

    @Value("${security.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}")
    private String trustedProxies;

    /**
     * 從 HttpServletRequest 解析真實 Client IP
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // 1. 自訂 header（如 Cloudflare 的 CF-Connecting-IP）
        if (ipHeader != null && !ipHeader.isBlank()) {
            String headerValue = request.getHeader(ipHeader);
            if (headerValue != null && !headerValue.isBlank()) {
                return headerValue.trim();
            }
        }

        // 2. X-Forwarded-For（取第一個 = 最原始的 client IP）
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        // 3. X-Real-IP
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }

        // 4. fallback
        return remoteAddr;
    }

    /**
     * 判斷 remoteAddr 是否為受信任的反向代理
     *
     * 支援：
     * - 精確 IP 比對（如 127.0.0.1）
     * - CIDR 範圍比對（如 172.16.0.0/12）
     */
    boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }

        // 快速路徑：localhost
        if ("127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return true;
        }

        if (trustedProxies == null || trustedProxies.isBlank()) {
            return false;
        }

        for (String entry : trustedProxies.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.contains("/")) {
                // CIDR 比對
                if (isInCidr(remoteAddr, trimmed)) {
                    return true;
                }
            } else {
                // 精確比對
                if (trimmed.equals(remoteAddr)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 判斷 IP 是否在 CIDR 範圍內
     *
     * @param ip   待檢查的 IP（如 172.18.0.4）
     * @param cidr CIDR 表示法（如 172.16.0.0/12）
     */
    private static boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;

            InetAddress network = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);
            InetAddress address = InetAddress.getByName(ip);

            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();

            // IPv4 vs IPv6 長度不同
            if (networkBytes.length != addressBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // 比對完整位元組
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addressBytes[i]) {
                    return false;
                }
            }

            // 比對剩餘 bits
            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((networkBytes[fullBytes] & mask) != (addressBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("CIDR 比對失敗: ip={} cidr={} error={}", ip, cidr, e.getMessage());
            return false;
        }
    }
}
