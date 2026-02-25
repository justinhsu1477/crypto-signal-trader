package com.trader.auth.config;

import com.trader.auth.filter.JwtAuthenticationFilter;
import com.trader.auth.filter.MonitorApiKeyFilter;
import com.trader.auth.filter.ReferralVerificationFilter;
import com.trader.auth.handler.CustomAccessDeniedHandler;
import com.trader.auth.handler.CustomAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
 * - /api/heartbeat → 需要認證（Monitor API Key）
 * - /api/subscription/webhook → 公開（Stripe callback）
 * - trading 端點 → 需要認證（JWT 或 API Key）
 * - /api/user/**, /api/dashboard/** → 需要認證（JWT）
 * - 其他 → 拒絕
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class AuthConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MonitorApiKeyFilter monitorApiKeyFilter;
    private final ReferralVerificationFilter referralVerificationFilter;
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
                        .requestMatchers("/api/health", "/api/health/deep").permitAll()
                        .requestMatchers("/api/subscription/webhook").permitAll()

                        // === ADMIN 專用：需要 ADMIN 角色（JWT ADMIN 或 Monitor API Key） ===
                        .requestMatchers(
                                "/api/execute-signal", "/api/broadcast-trade",
                                "/api/parse-signal"
                        ).hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // === 受保護：需要 JWT 或 Monitor API Key（任何角色） ===
                        .requestMatchers(
                                "/api/execute-trade",
                                "/api/balance", "/api/positions",
                                "/api/exchange-info", "/api/open-orders",
                                "/api/monitor-status", "/api/stream-status",
                                "/api/leverage", "/api/orders",
                                "/api/heartbeat"
                        ).authenticated()

                        // === 推薦系統：需要 JWT（ReferralVerificationFilter 白名單放行） ===
                        .requestMatchers("/api/referral/**").authenticated()

                        // === 受保護：SaaS 端點需要 JWT ===
                        .requestMatchers("/api/user/**").authenticated()
                        .requestMatchers("/api/dashboard/**").authenticated()
                        .requestMatchers("/api/subscription/**").authenticated()
                        .requestMatchers("/api/trades/**").authenticated()
                        .requestMatchers("/api/stats/**").authenticated()

                        // === 其他：全部拒絕 ===
                        .anyRequest().denyAll()
                )
                // Filter 順序：API Key → JWT → Referral Verification → Spring Security
                .addFilterBefore(monitorApiKeyFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(referralVerificationFilter,
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
