package com.trader.auth.handler;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.BinanceApiRateLimiter;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

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

    @Test
    @DisplayName("IllegalStateException message=null → 500（不含「用戶未登入」）")
    void handleIllegalState_nullMessage() {
        var ex = new IllegalStateException((String) null);
        ResponseEntity<ErrorResponse> resp = handler.handleIllegalState(ex);

        assertEquals(500, resp.getStatusCode().value());
        assertEquals("伺服器錯誤", resp.getBody().getError());
    }

    // ── 新增：Security 相關 ──

    @Test
    @DisplayName("AuthenticationException → 401")
    void handleAuth() {
        var ex = new BadCredentialsException("密碼錯誤");
        ResponseEntity<ErrorResponse> resp = handler.handleAuth(ex);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatusCode().value());
        assertEquals("認證失敗", resp.getBody().getError());
        assertEquals("密碼錯誤", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("AccessDeniedException → 403")
    void handleAccessDenied() {
        var ex = new AccessDeniedException("Access is denied");
        ResponseEntity<ErrorResponse> resp = handler.handleAccessDenied(ex);

        assertEquals(HttpStatus.FORBIDDEN.value(), resp.getStatusCode().value());
        assertEquals("存取被拒絕", resp.getBody().getError());
        assertEquals("您沒有權限存取此資源", resp.getBody().getMessage());
    }

    // ── 新增：JWT 相關 ──

    @Test
    @DisplayName("ExpiredJwtException → 401")
    void handleExpiredJwt() {
        var ex = new ExpiredJwtException(null, null, "JWT expired");
        ResponseEntity<ErrorResponse> resp = handler.handleExpiredJwt(ex);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatusCode().value());
        assertEquals("Token 已過期", resp.getBody().getError());
        assertEquals("請重新登入或使用 refresh token", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("JwtException → 401")
    void handleJwt() {
        var ex = new MalformedJwtException("malformed token");
        ResponseEntity<ErrorResponse> resp = handler.handleJwt(ex);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatusCode().value());
        assertEquals("Token 無效", resp.getBody().getError());
        assertEquals("malformed token", resp.getBody().getMessage());
    }

    // ── 新增：Validation ──

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 + 欄位錯誤訊息")
    void handleValidation() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "Email 不可為空"));
        bindingResult.addError(new FieldError("request", "password", "密碼至少 8 個字元"));

        var ex = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ErrorResponse> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertEquals("參數驗證失敗", resp.getBody().getError());
        assertTrue(resp.getBody().getMessage().contains("email"));
        assertTrue(resp.getBody().getMessage().contains("password"));
    }
}
