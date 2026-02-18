package com.trader.service;

import com.trader.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret",
                "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86400000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpirationMs", 604800000L);
    }

    @Nested
    @DisplayName("generateToken 生成 Token")
    class GenerateToken {

        @Test
        @DisplayName("生成的 Token 不為 null 或空白")
        void tokenNotNullOrBlank() {
            String token = jwtService.generateToken("user-001");

            assertThat(token).isNotNull();
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("生成的 Token 包含三段 (header.payload.signature)")
        void tokenHasThreeParts() {
            String token = jwtService.generateToken("user-001");

            assertThat(token.split("\\.")).hasSize(3);
        }
    }

    @Nested
    @DisplayName("extractUserId 提取用戶 ID")
    class ExtractUserId {

        @Test
        @DisplayName("從 Token 提取的 userId 與生成時相同")
        void extractUserIdMatchesGenerated() {
            String userId = "user-abc-123";
            String token = jwtService.generateToken(userId);

            String extracted = jwtService.extractUserId(token);

            assertThat(extracted).isEqualTo(userId);
        }

        @Test
        @DisplayName("不同 userId 產生的 Token 提取結果不同")
        void differentUserIdsDifferentTokens() {
            String token1 = jwtService.generateToken("user-001");
            String token2 = jwtService.generateToken("user-002");

            assertThat(jwtService.extractUserId(token1)).isEqualTo("user-001");
            assertThat(jwtService.extractUserId(token2)).isEqualTo("user-002");
        }
    }

    @Nested
    @DisplayName("validateToken 驗證 Token")
    class ValidateToken {

        @Test
        @DisplayName("有效 Token → 回傳 true")
        void validTokenReturnsTrue() {
            String token = jwtService.generateToken("user-001");

            assertThat(jwtService.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("過期 Token → 回傳 false")
        void expiredTokenReturnsFalse() throws InterruptedException {
            ReflectionTestUtils.setField(jwtService, "expirationMs", 1L);

            String token = jwtService.generateToken("user-001");
            Thread.sleep(50);

            assertThat(jwtService.validateToken(token)).isFalse();
        }

        @Test
        @DisplayName("篡改 Token → 回傳 false")
        void tamperedTokenReturnsFalse() {
            String token = jwtService.generateToken("user-001");
            String tampered = token.substring(0, token.lastIndexOf('.')) + ".tampered";

            assertThat(jwtService.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("隨機垃圾字串 → 回傳 false")
        void garbageStringReturnsFalse() {
            assertThat(jwtService.validateToken("not.a.jwt")).isFalse();
            assertThat(jwtService.validateToken("random-garbage-string")).isFalse();
            assertThat(jwtService.validateToken("")).isFalse();
        }

        @Test
        @DisplayName("使用不同 secret 簽名的 Token → 回傳 false")
        void wrongSecretReturnsFalse() {
            String token = jwtService.generateToken("user-001");

            JwtService otherService = new JwtService();
            ReflectionTestUtils.setField(otherService, "secret",
                    "another-secret-key-that-is-at-least-256-bits-long-for-testing");
            ReflectionTestUtils.setField(otherService, "expirationMs", 86400000L);
            ReflectionTestUtils.setField(otherService, "refreshExpirationMs", 604800000L);

            assertThat(otherService.validateToken(token)).isFalse();
        }
    }

    @Nested
    @DisplayName("generateRefreshToken 生成 Refresh Token")
    class GenerateRefreshToken {

        @Test
        @DisplayName("Refresh Token 不為 null 或空白")
        void refreshTokenNotNullOrBlank() {
            String refreshToken = jwtService.generateRefreshToken("user-001");

            assertThat(refreshToken).isNotNull();
            assertThat(refreshToken).isNotBlank();
        }

        @Test
        @DisplayName("Refresh Token 可驗證通過")
        void refreshTokenIsValid() {
            String refreshToken = jwtService.generateRefreshToken("user-001");

            assertThat(jwtService.validateToken(refreshToken)).isTrue();
        }

        @Test
        @DisplayName("Refresh Token 可提取正確 userId")
        void refreshTokenExtractsCorrectUserId() {
            String refreshToken = jwtService.generateRefreshToken("user-001");

            assertThat(jwtService.extractUserId(refreshToken)).isEqualTo("user-001");
        }

        @Test
        @DisplayName("Refresh Token 與一般 Token 不同")
        void refreshTokenDiffersFromAccessToken() {
            String accessToken = jwtService.generateToken("user-001");
            String refreshToken = jwtService.generateRefreshToken("user-001");

            assertThat(refreshToken).isNotEqualTo(accessToken);
        }
    }

    @Nested
    @DisplayName("getExpirationMs 過期時間")
    class GetExpirationMs {

        @Test
        @DisplayName("回傳設定的過期時間")
        void returnsConfiguredExpiration() {
            assertThat(jwtService.getExpirationMs()).isEqualTo(86400000L);
        }
    }

    @Nested
    @DisplayName("邊界情境")
    class EdgeCases {

        @Test
        @DisplayName("null Token 驗證 → 回傳 false")
        void nullTokenReturnsFalse() {
            assertThat(jwtService.validateToken(null)).isFalse();
        }

        @Test
        @DisplayName("extractUserId 對過期 Token → 拋出異常")
        void extractUserIdFromExpiredTokenThrows() throws InterruptedException {
            ReflectionTestUtils.setField(jwtService, "expirationMs", 1L);
            String token = jwtService.generateToken("user-001");
            Thread.sleep(50);

            assertThatThrownBy(() -> jwtService.extractUserId(token))
                    .isInstanceOf(Exception.class);
        }
    }
}
