package com.trader.auth.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 服務
 *
 * 負責 JWT 的生成、驗證、解析。
 * 使用 HMAC-SHA256 簽名，jjwt 0.12.6。
 */
@Slf4j
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token（24h）
     *
     * @param userId 用戶 ID
     * @return JWT token string
     */
    public String generateToken(String userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成 Refresh Token（7 天）
     *
     * @param userId 用戶 ID
     * @return refresh token string
     */
    public String generateRefreshToken(String userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 驗證 Token 是否有效
     *
     * @param token JWT token
     * @return true = 有效且未過期
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已過期: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT 驗證失敗: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT 格式錯誤: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 從 Token 中提取 userId
     *
     * @param token JWT token
     * @return userId (subject claim)
     */
    public String extractUserId(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * 取得 Token 過期時間（毫秒）
     */
    public long getExpirationMs() {
        return expirationMs;
    }
}
