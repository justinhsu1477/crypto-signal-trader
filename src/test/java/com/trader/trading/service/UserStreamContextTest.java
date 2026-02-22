package com.trader.trading.service;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * UserStreamContext 單元測試
 */
class UserStreamContextTest {

    private UserStreamContext context;

    @BeforeEach
    void setUp() {
        context = new UserStreamContext("user-1", "api-key-123", "secret-key-456");
    }

    @Nested
    @DisplayName("初始化狀態")
    class InitialState {

        @Test
        @DisplayName("不可變欄位正確設定")
        void immutableFieldsSetCorrectly() {
            assertThat(context.getUserId()).isEqualTo("user-1");
            assertThat(context.getApiKey()).isEqualTo("api-key-123");
            assertThat(context.getSecretKey()).isEqualTo("secret-key-456");
            assertThat(context.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("連線狀態初始為斷開")
        void initialConnectionState() {
            assertThat(context.isConnected()).isFalse();
            assertThat(context.isSelfInitiatedClose()).isFalse();
            assertThat(context.isAlertSent()).isFalse();
            assertThat(context.getListenKey()).isNull();
            assertThat(context.getWebSocket()).isNull();
        }

        @Test
        @DisplayName("重連計數初始為 0")
        void initialReconnectState() {
            assertThat(context.getReconnectAttempts()).isEqualTo(0);
            assertThat(context.getLastMessageTime()).isNull();
            assertThat((Object) context.getPendingReconnect()).isNull();
        }
    }

    @Nested
    @DisplayName("重連狀態管理")
    class ReconnectState {

        @Test
        @DisplayName("incrementReconnectAttempts 正確遞增並返回新值")
        void incrementReturnsNewValue() {
            assertThat(context.incrementReconnectAttempts()).isEqualTo(1);
            assertThat(context.incrementReconnectAttempts()).isEqualTo(2);
            assertThat(context.incrementReconnectAttempts()).isEqualTo(3);
            assertThat(context.getReconnectAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("resetReconnectAttempts 歸零")
        void resetClearsAttempts() {
            context.incrementReconnectAttempts();
            context.incrementReconnectAttempts();
            context.resetReconnectAttempts();
            assertThat(context.getReconnectAttempts()).isEqualTo(0);
        }

        @Test
        @DisplayName("resetOnConnected 重置所有連線狀態")
        void resetOnConnectedResetsAll() {
            // 模擬斷線重連後
            context.incrementReconnectAttempts();
            context.incrementReconnectAttempts();
            context.setSelfInitiatedClose(true);
            context.setConnected(false);

            context.resetOnConnected();

            assertThat(context.isConnected()).isTrue();
            assertThat(context.isSelfInitiatedClose()).isFalse();
            assertThat(context.getReconnectAttempts()).isEqualTo(0);
            assertThat(context.getLastMessageTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("訊息時間追蹤")
    class MessageTimeTracking {

        @Test
        @DisplayName("updateLastMessageTime 設定當前時間")
        void updatesLastMessageTime() {
            assertThat(context.getLastMessageTime()).isNull();
            context.updateLastMessageTime();
            assertThat(context.getLastMessageTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("狀態查詢")
    class StatusQuery {

        @Test
        @DisplayName("getStatus 包含所有必要欄位")
        void statusContainsAllFields() {
            Map<String, Object> status = context.getStatus();

            assertThat(status).containsKey("userId");
            assertThat(status).containsKey("connected");
            assertThat(status).containsKey("listenKeyActive");
            assertThat(status).containsKey("lastMessageTime");
            assertThat(status).containsKey("elapsedSeconds");
            assertThat(status).containsKey("reconnectAttempts");
            assertThat(status).containsKey("alertSent");
        }

        @Test
        @DisplayName("初始狀態的 status 值正確")
        void initialStatusValues() {
            Map<String, Object> status = context.getStatus();

            assertThat(status.get("userId")).isEqualTo("user-1");
            assertThat(status.get("connected")).isEqualTo(false);
            assertThat(status.get("listenKeyActive")).isEqualTo(false);
            assertThat(status.get("lastMessageTime")).isEqualTo("never");
            assertThat(status.get("reconnectAttempts")).isEqualTo(0);
            assertThat(status.get("alertSent")).isEqualTo(false);
        }

        @Test
        @DisplayName("連線後 status 更新")
        void statusAfterConnection() {
            context.setListenKey("test-listen-key");
            context.resetOnConnected();

            Map<String, Object> status = context.getStatus();

            assertThat(status.get("connected")).isEqualTo(true);
            assertThat(status.get("listenKeyActive")).isEqualTo(true);
            assertThat(status.get("lastMessageTime")).isNotEqualTo("never");
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString 包含 userId 和 connected 狀態")
        void toStringContainsKeyInfo() {
            String str = context.toString();
            assertThat(str).contains("user-1");
            assertThat(str).contains("connected=false");
            assertThat(str).contains("attempts=0");
        }
    }
}
