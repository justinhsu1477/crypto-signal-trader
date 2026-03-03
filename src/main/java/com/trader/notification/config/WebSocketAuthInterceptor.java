package com.trader.notification.config;

import com.trader.auth.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WebSocket STOMP 認證攔截器
 *
 * <pre>
 * 在 STOMP CONNECT 階段驗證用戶身份：
 *   1. 嘗試從 STOMP header "Authorization" 提取 Bearer token
 *   2. 嘗試從 WebSocket handshake 的 Cookie 提取 access_token
 *   3. 驗證 JWT → 設定 Authentication（userId + role）
 *
 * 面試重點：
 *   - WebSocket handshake 是 HTTP 升級請求，瀏覽器會自動帶 Cookie
 *   - STOMP CONNECT 是 WebSocket 連線後的第一個 frame
 *   - SockJS fallback（XHR polling）也會帶 Cookie（credentials: include）
 *   - 這裡不強制認證 → 未認證用戶也能連線但只能訂閱公開 topic
 *     （公告 /topic/announcements 是公開的，不需要個人化資料）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateFromStompHeaders(accessor);
        }

        return message;
    }

    private void authenticateFromStompHeaders(StompHeaderAccessor accessor) {
        String token = null;

        // 1. 嘗試 STOMP header: Authorization: Bearer xxx
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        // 2. Fallback: 從 handshake Cookie 提取（SockJS 場景）
        if (token == null) {
            @SuppressWarnings("unchecked")
            var sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                Object cookieToken = sessionAttributes.get("access_token");
                if (cookieToken instanceof String s && !s.isBlank()) {
                    token = s;
                }
            }
        }

        if (token == null || token.isBlank()) {
            log.debug("WebSocket CONNECT 無 JWT，允許匿名連線");
            return;
        }

        try {
            if (!jwtService.validateToken(token)) {
                log.warn("WebSocket CONNECT JWT 驗證失敗");
                return;
            }

            String userId = jwtService.extractUserId(token);
            String role = jwtService.extractRole(token);

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );

            accessor.setUser(auth);
            log.debug("WebSocket 認證成功: userId={}, role={}", userId, role);
        } catch (Exception e) {
            log.warn("WebSocket CONNECT 認證異常: {}", e.getMessage());
        }
    }
}
