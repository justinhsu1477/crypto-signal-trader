package com.trader.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT Token 服務
 *
 * 負責 JWT 的生成、驗證、解析。
 *
 * TODO: 使用 jjwt 庫實作完整邏輯
 */
@Slf4j
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    /**
     * 生成 JWT Token
     *
     * @param userId 用戶 ID
     * @return JWT token string
     */
    public String generateToken(String userId) {
        // TODO: 使用 Jwts.builder() 建立 token
        //   .subject(userId)
        //   .issuedAt(now)
        //   .expiration(now + expirationMs)
        //   .signWith(key)
        //   .compact()
        throw new UnsupportedOperationException("JWT generateToken 尚未實作");
    }

    /**
     * 生成 Refresh Token（較長效期）
     *
     * @param userId 用戶 ID
     * @return refresh token string
     */
    public String generateRefreshToken(String userId) {
        // TODO: 類似 generateToken 但效期更長（例如 7 天）
        throw new UnsupportedOperationException("JWT generateRefreshToken 尚未實作");
    }

    /**
     * 驗證 Token 是否有效
     *
     * @param token JWT token
     * @return true = 有效且未過期
     */
    public boolean validateToken(String token) {
        // TODO: parse token, 檢查簽名和過期時間
        throw new UnsupportedOperationException("JWT validateToken 尚未實作");
    }

    /**
     * 從 Token 中提取 userId
     *
     * @param token JWT token
     * @return userId (subject claim)
     */
    public String extractUserId(String token) {
        // TODO: parse token, 取得 subject
        throw new UnsupportedOperationException("JWT extractUserId 尚未實作");
    }
}
