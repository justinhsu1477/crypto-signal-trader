package com.trader.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域資源共享配置
 *
 * 允許前端（localhost:3000）訪問後端 API（localhost:8080）。
 *
 * 為什麼需要？
 * - 瀏覽器的同源政策 (Same-Origin Policy)：
 *   http://localhost:3000 ≠ http://localhost:8080
 * - 不同端口被視為不同源，默認被瀏覽器阻止
 *
 * 此配置後效果：
 * 1. 前端可以發送 HTTP 請求到後端
 * 2. 瀏覽器允許接收跨域回應
 * 3. OPTIONS 預檢請求自動通過
 *
 * 安全考慮：
 * - Dev: 允許 localhost:3000 和 localhost:3001
 * - Prod: 應限制為真實的前端域名（如 example.com）
 */
@Configuration
@Slf4j
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 允許的源（origin）
                .allowedOrigins(
                        "http://localhost:3000",    // 開發環境 - Next.js dev server
                        "http://localhost:3001",    // 開發環境 - Next.js 另一實例
                        "http://127.0.0.1:3000",    // 同上（127.0.0.1 版本）
                        "http://127.0.0.1:3001"
                )
                // 允許的 HTTP 方法
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                // 允許的 request header
                .allowedHeaders(
                        "Authorization",            // JWT token
                        "Content-Type",             // application/json
                        "Accept",
                        "Origin",
                        "X-Requested-With",
                        "X-CSRF-TOKEN"
                )
                // 允許暴露給前端的 response header
                .exposedHeaders(
                        "Authorization",            // 新的 token（刷新後）
                        "X-Total-Count"             // 分頁用的總數
                )
                // 允許發送認證信息（如 Cookie）
                .allowCredentials(true)
                // 預檢請求的快取時間（秒）
                .maxAge(3600);

        log.info("CORS 設定已啟用: localhost:3000, localhost:3001");
    }
}
