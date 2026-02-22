package com.trader.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CustomAuthenticationEntryPoint 單元測試
 *
 * 覆蓋：401 回傳格式, error message, audit 記錄
 */
class CustomAuthenticationEntryPointTest {

    private ObjectMapper objectMapper;
    private AuditService auditService;
    private CustomAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper(); // 用真實的 ObjectMapper
        auditService = mock(AuditService.class);
        entryPoint = new CustomAuthenticationEntryPoint(objectMapper, auditService);
    }

    @Test
    @DisplayName("回傳 401 + JSON 格式")
    void returns401Json() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        when(request.getRequestURI()).thenReturn("/api/dashboard/overview");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        entryPoint.commence(request, response,
                new BadCredentialsException("Full authentication is required"));

        verify(response).setStatus(401);
        verify(response).setContentType("application/json");
        String json = sw.toString();
        assertThat(json).contains("401");
        assertThat(json).contains("Bearer Token");
    }

    @Test
    @DisplayName("呼叫 auditService 記錄")
    void callsAuditService() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(request.getRequestURI()).thenReturn("/api/user/profile");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("Authorization")).thenReturn("Bearer expired");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        entryPoint.commence(request, response,
                new BadCredentialsException("Token expired"));

        verify(auditService).log(
                isNull(),
                eq("API_ACCESS_FAILED"),
                eq("/api/user/profile"),
                eq("FAILED"),
                eq("10.0.0.1"),
                contains("Invalid token")
        );
    }

    @Test
    @DisplayName("X-Forwarded-For IP 正確解析")
    void xForwardedForIpParsed() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        entryPoint.commence(request, response,
                new BadCredentialsException("unauthorized"));

        verify(auditService).log(
                isNull(), anyString(), anyString(), anyString(),
                eq("203.0.113.50"),
                anyString()
        );
    }
}
