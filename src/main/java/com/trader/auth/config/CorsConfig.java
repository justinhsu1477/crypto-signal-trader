package com.trader.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域資源共享配置
 *
 * 允許前端訪問後端 API。
 *
 * 配置方式：
 * - 環境變數 CORS_ALLOWED_ORIGINS（逗號分隔）→ 優先使用
 * - dev profile：自動加入 localhost:3000, localhost:3001（方便本地調試）
 * - prod profile：只允許 CORS_ALLOWED_ORIGINS 指定的域名
 *
 * 正式環境範例：
 *   CORS_ALLOWED_ORIGINS=https://yourdomain.com,https://www.yourdomain.com
 *
 * 面試重點：
 *   - 生產環境不該允許 localhost origin，否則用戶本機惡意網站可帶 cookie 打 API
 *   - Spring Profiles 區分 dev/prod 配置，避免硬編碼環境判斷
 */
@Configuration
@Slf4j
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:}")
    private String corsAllowedOrigins;

    private final Environment environment;

    public CorsConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = resolveAllowedOrigins();

        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders(
                        "Authorization",
                        "Content-Type",
                        "Accept",
                        "Origin",
                        "X-Requested-With",
                        "X-CSRF-TOKEN"
                )
                .exposedHeaders(
                        "Authorization",
                        "X-Total-Count"
                )
                .allowCredentials(true)
                .maxAge(3600);

        // WebSocket handshake 也需要 CORS（SockJS 降級走 XHR 時需要）
        registry.addMapping("/ws/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);

        log.info("CORS 設定已啟用: {}", origins);
    }

    private List<String> resolveAllowedOrigins() {
        List<String> origins = new ArrayList<>();

        // 1. 環境變數指定的域名（正式環境用）
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            Arrays.stream(corsAllowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(origins::add);
        }

        // 2. 僅 dev profile 才加入 localhost（生產環境不允許）
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("dev")) {
            List<String> devOrigins = List.of(
                    "http://localhost",
                    "http://localhost:3000",
                    "http://localhost:3001",
                    "http://127.0.0.1",
                    "http://127.0.0.1:3000",
                    "http://127.0.0.1:3001"
            );
            for (String dev : devOrigins) {
                if (!origins.contains(dev)) {
                    origins.add(dev);
                }
            }
            log.debug("Dev profile 偵測到，已加入 localhost origins");
        }

        if (origins.isEmpty()) {
            log.warn("CORS: 無允許的 origins！請設定 CORS_ALLOWED_ORIGINS 環境變數");
        }

        return origins;
    }
}
