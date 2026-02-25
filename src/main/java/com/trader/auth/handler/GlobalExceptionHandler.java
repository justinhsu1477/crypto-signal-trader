package com.trader.auth.handler;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.BinanceApiRateLimiter;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全域例外處理
 *
 * 統一攔截所有未處理的例外，回傳一致的 {@link ErrorResponse} JSON 格式。
 * 優先順序：具體例外 → 通用例外 → fallback
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                        .error("認證失敗")
                        .message(e.getMessage())
                        .build());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.builder()
                        .error("存取被拒絕")
                        .message("您沒有權限存取此資源")
                        .build());
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<ErrorResponse> handleExpiredJwt(ExpiredJwtException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                        .error("Token 已過期")
                        .message("請重新登入或使用 refresh token")
                        .build());
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponse> handleJwt(JwtException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                        .error("Token 無效")
                        .message(e.getMessage())
                        .build());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        if (e.getMessage() != null && e.getMessage().contains("用戶未登入")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder()
                            .error("未登入")
                            .message("請先登入")
                            .build());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .error("伺服器錯誤")
                        .message(e.getMessage())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("參數驗證失敗");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .error("參數驗證失敗")
                        .message(message)
                        .build());
    }

    // ── 新增：Binance API 限流 ──

    @ExceptionHandler(BinanceApiRateLimiter.RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            BinanceApiRateLimiter.RateLimitExceededException e) {
        log.warn("Binance API 限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.builder()
                        .error("API 請求限流")
                        .message(e.getMessage())
                        .build());
    }

    // ── 新增：業務參數錯誤 ──

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .error("請求參數錯誤")
                        .message(e.getMessage())
                        .build());
    }

    // ── 新增：資料庫異常 ──

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e) {
        log.error("資料庫存取異常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.builder()
                        .error("資料庫暫時無法存取")
                        .message("請稍後再試")
                        .build());
    }

    // ── 新增：通用 fallback（最後防線） ──

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未預期的例外: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .error("伺服器內部錯誤")
                        .message("發生非預期錯誤，請稍後再試")
                        .build());
    }
}
