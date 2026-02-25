package com.trader.auth.handler;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.BinanceApiRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 單元測試
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("RateLimitExceededException → 429")
    void handleRateLimit() {
        var ex = new BinanceApiRateLimiter.RateLimitExceededException("weight 超過上限");
        ResponseEntity<ErrorResponse> resp = handler.handleRateLimit(ex);

        assertEquals(429, resp.getStatusCode().value());
        assertEquals("API 請求限流", resp.getBody().getError());
        assertEquals("weight 超過上限", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("IllegalArgumentException → 400")
    void handleIllegalArgument() {
        var ex = new IllegalArgumentException("Email 已被註冊");
        ResponseEntity<ErrorResponse> resp = handler.handleIllegalArgument(ex);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("請求參數錯誤", resp.getBody().getError());
        assertEquals("Email 已被註冊", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("DataAccessException → 503，不暴露 SQL 細節")
    void handleDataAccess() {
        var ex = new DataAccessResourceFailureException("Connection refused");
        ResponseEntity<ErrorResponse> resp = handler.handleDataAccess(ex);

        assertEquals(503, resp.getStatusCode().value());
        assertEquals("資料庫暫時無法存取", resp.getBody().getError());
        assertEquals("請稍後再試", resp.getBody().getMessage());
        // 不應暴露底層錯誤訊息
        assertFalse(resp.getBody().getMessage().contains("Connection"));
    }

    @Test
    @DisplayName("未知 Exception → 500，隱藏內部細節")
    void handleUnexpected() {
        var ex = new NullPointerException("some.field is null");
        ResponseEntity<ErrorResponse> resp = handler.handleUnexpected(ex);

        assertEquals(500, resp.getStatusCode().value());
        assertEquals("伺服器內部錯誤", resp.getBody().getError());
        // 不應暴露 NPE 細節
        assertFalse(resp.getBody().getMessage().contains("null"));
    }

    @Test
    @DisplayName("IllegalStateException 含「用戶未登入」→ 401")
    void handleIllegalState_unauthorized() {
        var ex = new IllegalStateException("用戶未登入或 token 無效");
        ResponseEntity<ErrorResponse> resp = handler.handleIllegalState(ex);

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("未登入", resp.getBody().getError());
    }

    @Test
    @DisplayName("一般 IllegalStateException → 500")
    void handleIllegalState_generic() {
        var ex = new IllegalStateException("unexpected state");
        ResponseEntity<ErrorResponse> resp = handler.handleIllegalState(ex);

        assertEquals(500, resp.getStatusCode().value());
        assertEquals("伺服器錯誤", resp.getBody().getError());
    }
}
