package com.trader.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CustomAccessDeniedHandler 單元測試
 *
 * 覆蓋：403 回傳格式, 正確 error message
 */
class CustomAccessDeniedHandlerTest {

    private ObjectMapper objectMapper;
    private CustomAccessDeniedHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new CustomAccessDeniedHandler(objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("回傳 403 + JSON 格式")
    void returns403Json() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        handler.handle(request, response, new AccessDeniedException("Access is denied"));

        verify(response).setStatus(403);
        verify(response).setContentType("application/json");
        String json = sw.toString();
        assertThat(json).contains("403");
        assertThat(json).contains("權限");
    }

    @Test
    @DisplayName("未認證用戶 — userId 為 unknown")
    void unknownUserWhenNotAuthenticated() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        // 不設定 SecurityContext → getCurrentUserId 拋例外 → fallback 到 "unknown"
        assertThatCode(() ->
                handler.handle(request, response, new AccessDeniedException("Denied"))
        ).doesNotThrowAnyException();
    }
}
