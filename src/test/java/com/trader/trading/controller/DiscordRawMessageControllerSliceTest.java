package com.trader.trading.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.auth.config.AuthConfig;
import com.trader.auth.filter.JwtAuthenticationFilter;
import com.trader.auth.filter.MonitorApiKeyFilter;
import com.trader.auth.handler.CustomAccessDeniedHandler;
import com.trader.auth.handler.CustomAuthenticationEntryPoint;
import com.trader.auth.service.JwtService;
import com.trader.auth.util.ClientIpResolver;
import com.trader.shared.service.AuditService;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.service.DiscordRawMessageService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DiscordRawMessageController Slice Test
 *
 * 用 @WebMvcTest 只載入 controller + Spring Security 設定，
 * 不啟 Docker、不連 DB。涵蓋：
 * - Auth: 缺 / 錯 X-Api-Key → 401/403
 * - Validation: 缺 message_id 或 channel_id → 400
 * - Happy path: 合法 payload → 200，service 被呼叫
 */
@WebMvcTest(controllers = DiscordRawMessageController.class)
@Import({AuthConfig.class, MonitorApiKeyFilter.class, JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "monitor.api-key=test-monitor-key",
        "jwt.secret=test-secret-key-for-slice-test-minimum-256-bits-long-enough",
        "jwt.expiration-ms=1800000",
        "jwt.refresh-expiration-ms=259200000"
})
@DisplayName("DiscordRawMessageController — Slice Test (@WebMvcTest)")
class DiscordRawMessageControllerSliceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private DiscordRawMessageService service;

    // Auth filter 依賴
    @MockBean private JwtService jwtService;
    @MockBean private UserRepository userRepository;
    @MockBean private AuditService auditService;
    @MockBean private ClientIpResolver clientIpResolver;

    private static final String MONITOR_API_KEY = "test-monitor-key";

    private String validPayload() {
        return """
                {
                  "message_id": "msg-slice-001",
                  "channel_id": "ch-1",
                  "channel_name": "vip",
                  "guild_id": "g-1",
                  "author_name": "陳哥",
                  "message_timestamp": "2026-05-11T10:00:00",
                  "content": "BTC 多單 60000",
                  "has_attachments": false,
                  "attachment_count": 0,
                  "parser_action": "ENTRY"
                }
                """;
    }

    @Nested
    @DisplayName("Group A: Auth")
    class AuthTests {

        @Test
        @DisplayName("postWithoutAuth → 401 or 403")
        void postWithoutAuth_returns401or403() throws Exception {
            mockMvc.perform(post("/api/discord-messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validPayload()))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status)
                                .as("missing API key should be 401 or 403, got " + status)
                                .isIn(401, 403);
                    });

            verify(service, never()).recordMessage(any());
        }

        @Test
        @DisplayName("postWithWrongApiKey → 401 or 403")
        void postWithWrongApiKey_returns401or403() throws Exception {
            mockMvc.perform(post("/api/discord-messages")
                            .header("X-Api-Key", "wrong-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validPayload()))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status).isIn(401, 403);
                    });

            verify(service, never()).recordMessage(any());
        }
    }

    @Nested
    @DisplayName("Group B: Validation")
    class ValidationTests {

        @Test
        @DisplayName("postWithoutMessageId → 400")
        void postWithoutMessageId_returns400() throws Exception {
            String payload = """
                    {
                      "channel_id": "ch-1",
                      "content": "BTC"
                    }
                    """;
            mockMvc.perform(post("/api/discord-messages")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .containsIgnoringCase("message_id"));

            verify(service, never()).recordMessage(any());
        }

        @Test
        @DisplayName("postWithoutChannelId → 400")
        void postWithoutChannelId_returns400() throws Exception {
            String payload = """
                    {
                      "message_id": "msg-1",
                      "content": "BTC"
                    }
                    """;
            mockMvc.perform(post("/api/discord-messages")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .containsIgnoringCase("channel_id"));

            verify(service, never()).recordMessage(any());
        }
    }

    @Nested
    @DisplayName("Group C: Happy path")
    class RoutingTests {

        @Test
        @DisplayName("postWithValidPayload → 200, service 被呼叫")
        void postWithValidPayload_returns200() throws Exception {
            DiscordRawMessage saved = DiscordRawMessage.builder()
                    .id(1L)
                    .messageId("msg-slice-001")
                    .sourceChannelId("ch-1")
                    .build();
            when(service.recordMessage(any())).thenReturn(saved);

            mockMvc.perform(post("/api/discord-messages")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validPayload()))
                    .andExpect(status().isOk())
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        assertThat(body).contains("msg-slice-001");
                    });

            verify(service).recordMessage(any());
        }
    }
}
