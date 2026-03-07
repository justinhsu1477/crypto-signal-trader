package com.trader.dashboard.controller;

import com.trader.dashboard.dto.AdminSendNotificationRequest;
import com.trader.notification.service.NotificationService;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminNotificationController 單元測試
 *
 * 覆蓋：
 * - 正常發送給多個用戶
 * - 部分 userId 無效
 * - 全部 userId 無效 → 400
 * - 發送過程異常 → failCount++
 * - 顏色解析
 * - AuditService 呼叫
 */
class AdminNotificationControllerTest {

    private NotificationService notificationService;
    private UserRepository userRepository;
    private AuditService auditService;
    private AdminNotificationController controller;

    private MockedStatic<SecurityUtil> securityUtilMock;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);

        controller = new AdminNotificationController(notificationService, userRepository, auditService);

        securityUtilMock = mockStatic(SecurityUtil.class);
        securityUtilMock.when(SecurityUtil::getCurrentUserId).thenReturn("admin-001");
    }

    @AfterEach
    void tearDown() {
        securityUtilMock.close();
    }

    // ==================== 正常發送 ====================

    @Nested
    @DisplayName("正常發送")
    class NormalSend {

        @Test
        @DisplayName("發送給 2 個有效用戶 → successCount=2, failCount=0")
        void sendToTwoValidUsers() {
            when(userRepository.existsById("user-1")).thenReturn(true);
            when(userRepository.existsById("user-2")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1", "user-2"));
            request.setTitle("系統維護通知");
            request.setMessage("系統將於今晚 10 點維護");
            request.setColor("BLUE");

            ResponseEntity<?> response = controller.send(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("successCount")).isEqualTo(2);
            assertThat(body.get("failCount")).isEqualTo(0);
            assertThat(body.get("totalUsers")).isEqualTo(2);

            // 驗證通知發送（標題帶 📢 前綴）
            verify(notificationService).sendNotificationToUser(
                    eq("user-1"), eq("📢 系統維護通知"), eq("系統將於今晚 10 點維護"),
                    eq(NotificationService.COLOR_BLUE));
            verify(notificationService).sendNotificationToUser(
                    eq("user-2"), eq("📢 系統維護通知"), eq("系統將於今晚 10 點維護"),
                    eq(NotificationService.COLOR_BLUE));
        }

        @Test
        @DisplayName("color 為 null → 預設 BLUE")
        void nullColorDefaultsToBlue() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("測試");
            request.setMessage("內容");
            request.setColor(null);

            controller.send(request);

            verify(notificationService).sendNotificationToUser(
                    eq("user-1"), anyString(), anyString(),
                    eq(NotificationService.COLOR_BLUE));
        }
    }

    // ==================== 顏色解析 ====================

    @Nested
    @DisplayName("顏色解析")
    class ColorParsing {

        @Test
        @DisplayName("GREEN → COLOR_GREEN")
        void greenColor() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("好消息");
            request.setMessage("上線");
            request.setColor("GREEN");

            controller.send(request);

            verify(notificationService).sendNotificationToUser(
                    anyString(), anyString(), anyString(),
                    eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("RED → COLOR_RED")
        void redColor() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("緊急");
            request.setMessage("告警");
            request.setColor("RED");

            controller.send(request);

            verify(notificationService).sendNotificationToUser(
                    anyString(), anyString(), anyString(),
                    eq(NotificationService.COLOR_RED));
        }

        @Test
        @DisplayName("YELLOW → COLOR_YELLOW")
        void yellowColor() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("注意");
            request.setMessage("警告");
            request.setColor("YELLOW");

            controller.send(request);

            verify(notificationService).sendNotificationToUser(
                    anyString(), anyString(), anyString(),
                    eq(NotificationService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("未知顏色 → 預設 BLUE")
        void unknownColorDefaultsToBlue() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("測試");
            request.setMessage("內容");
            request.setColor("PURPLE");

            controller.send(request);

            verify(notificationService).sendNotificationToUser(
                    anyString(), anyString(), anyString(),
                    eq(NotificationService.COLOR_BLUE));
        }
    }

    // ==================== 無效 userId ====================

    @Nested
    @DisplayName("無效 userId 處理")
    class InvalidUserIds {

        @Test
        @DisplayName("部分 userId 無效 → 只發給有效的 + invalidUserIds 回傳")
        void partialInvalidUserIds() {
            when(userRepository.existsById("user-1")).thenReturn(true);
            when(userRepository.existsById("bad-user")).thenReturn(false);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1", "bad-user"));
            request.setTitle("通知");
            request.setMessage("內容");

            ResponseEntity<?> response = controller.send(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("successCount")).isEqualTo(1);
            assertThat(body.get("totalUsers")).isEqualTo(1);
            @SuppressWarnings("unchecked")
            List<String> invalidIds = (List<String>) body.get("invalidUserIds");
            assertThat(invalidIds).containsExactly("bad-user");

            // 只發給有效用戶
            verify(notificationService, times(1)).sendNotificationToUser(
                    anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("全部 userId 無效 → 400 錯誤")
        void allInvalidUserIds() {
            when(userRepository.existsById("bad-1")).thenReturn(false);
            when(userRepository.existsById("bad-2")).thenReturn(false);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("bad-1", "bad-2"));
            request.setTitle("通知");
            request.setMessage("內容");

            ResponseEntity<?> response = controller.send(request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("error")).isEqualTo("所有用戶 ID 無效");

            // 不應發送任何通知
            verify(notificationService, never()).sendNotificationToUser(
                    anyString(), anyString(), anyString(), anyInt());
        }
    }

    // ==================== 異常處理 ====================

    @Nested
    @DisplayName("異常處理")
    class ErrorHandling {

        @Test
        @DisplayName("發送過程異常 → failCount++ 但不中斷")
        void sendFailureIncreasesFailCount() {
            when(userRepository.existsById("user-1")).thenReturn(true);
            when(userRepository.existsById("user-2")).thenReturn(true);

            doThrow(new RuntimeException("Discord 連線失敗"))
                    .when(notificationService).sendNotificationToUser(
                            eq("user-1"), anyString(), anyString(), anyInt());

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1", "user-2"));
            request.setTitle("通知");
            request.setMessage("內容");

            ResponseEntity<?> response = controller.send(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("successCount")).isEqualTo(1);
            assertThat(body.get("failCount")).isEqualTo(1);

            // 兩個都嘗試發送
            verify(notificationService, times(2)).sendNotificationToUser(
                    anyString(), anyString(), anyString(), anyInt());
        }
    }

    // ==================== AuditService ====================

    @Nested
    @DisplayName("AuditService 記錄")
    class AuditLogging {

        @Test
        @DisplayName("發送後記錄 audit log")
        void logsAuditEntry() {
            when(userRepository.existsById("user-1")).thenReturn(true);

            AdminSendNotificationRequest request = new AdminSendNotificationRequest();
            request.setUserIds(List.of("user-1"));
            request.setTitle("維護通知");
            request.setMessage("內容");

            controller.send(request);

            verify(auditService).log(
                    eq("admin-001"),
                    eq("SEND_NOTIFICATION"),
                    eq("/api/admin/notifications/send"),
                    eq("SUCCESS"),
                    eq(""),
                    contains("維護通知"));
        }
    }
}
