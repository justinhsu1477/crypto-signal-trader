package com.trader.auth.config;

import com.trader.auth.filter.JwtAuthenticationFilter;
import com.trader.auth.filter.MonitorApiKeyFilter;
import com.trader.auth.handler.CustomAccessDeniedHandler;
import com.trader.auth.handler.CustomAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 設定
 *
 * 認證方式：
 * 1. JWT (Bearer Token) — 前端用戶使用
 * 2. API Key (X-Api-Key) — Python Monitor 內部服務使用
 *
 * 路徑規則：
 * - /api/auth/** → 公開（登入、註冊、刷新 token）
 * - /api/heartbeat → 公開（健康檢查）
 * - /api/subscription/webhook → 公開（Stripe callback）
 * - trading 端點 → 需要認證（JWT 或 API Key）
 * - /api/user/**, /api/dashboard/** → 需要認證（JWT）
 * - 其他 → 拒絕
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class AuthConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MonitorApiKeyFilter monitorApiKeyFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // === 公開端點 ===
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/heartbeat").permitAll()
                        .requestMatchers("/api/subscription/webhook").permitAll()

                        // === 受保護：需要 JWT 或 Monitor API Key ===
                        .requestMatchers(
                                "/api/execute-signal", "/api/execute-trade",
                                "/api/broadcast-trade", "/api/parse-signal",
                                "/api/balance", "/api/positions",
                                "/api/exchange-info", "/api/open-orders",
                                "/api/monitor-status", "/api/stream-status",
                                "/api/leverage", "/api/orders"
                        ).authenticated()

                        // === 受保護：SaaS 端點需要 JWT ===
                        .requestMatchers("/api/user/**").authenticated()
                        .requestMatchers("/api/dashboard/**").authenticated()
                        .requestMatchers("/api/subscription/**").authenticated()
                        .requestMatchers("/api/trades/**").authenticated()
                        .requestMatchers("/api/stats/**").authenticated()
                        .requestMatchers("/api/admin/**").authenticated()

                        // === 其他：全部拒絕 ===
                        .anyRequest().denyAll()
                )
                // Filter 順序：API Key → JWT → Spring Security
                .addFilterBefore(monitorApiKeyFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
