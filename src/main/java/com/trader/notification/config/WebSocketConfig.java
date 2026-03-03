package com.trader.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 配置（STOMP over SockJS + SimpleBroker）
 *
 * <pre>
 * 架構：
 *   Client (SockJS/STOMP) ──→ /ws endpoint ──→ SimpleBroker
 *       │                                          │
 *       └── subscribe /topic/announcements ←──────┘
 *
 * 面試重點：
 *   - STOMP: Simple Text Oriented Messaging Protocol
 *     → 在 WebSocket 上層加入 subscribe / send 語義（類似 pub/sub）
 *   - SockJS: WebSocket 降級方案
 *     → 瀏覽器不支援 WebSocket 時自動降級為 XHR polling
 *   - SimpleBroker: 記憶體內 message broker
 *     → 適合單實例，升級方案：改用 StompBrokerRelay 轉發到 RabbitMQ STOMP plugin
 *
 * 升級到 RabbitMQ STOMP Relay 時只需改 configureMessageBroker：
 *   config.enableStompBrokerRelay("/topic")
 *         .setRelayHost("localhost").setRelayPort(61613)
 *         .setClientLogin("guest").setClientPasscode("guest");
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // SimpleBroker 管理 /topic 目的地（pub/sub 模式）
        config.enableSimpleBroker("/topic");
        // Client 發送訊息的前綴（本專案目前不需要 client → server 訊息，預留）
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket handshake 端點，SockJS fallback
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // STOMP CONNECT 時驗證 JWT（從 Cookie 或 header 提取）
        registration.interceptors(webSocketAuthInterceptor);
    }
}
