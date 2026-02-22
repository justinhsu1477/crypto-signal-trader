package com.trader.shared.service;

import com.trader.shared.entity.AuditLog;
import com.trader.shared.repository.AuditLogRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuditService 單元測試
 *
 * 覆蓋：log 建立、repository 儲存、欄位驗證、例外不拋出
 */
class AuditServiceTest {

    private AuditLogRepository auditLogRepository;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        auditService = new AuditService(auditLogRepository);
    }

    @Nested
    @DisplayName("log 方法")
    class LogTests {

        @Test
        @DisplayName("正確建立 AuditLog 並儲存")
        void createsAndSavesAuditLog() {
            auditService.log("user-1", "LOGIN", "/api/auth/login", "SUCCESS", "192.168.1.1", "");

            verify(auditLogRepository).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("ArgumentCaptor 驗證欄位正確")
        void verifyFieldsWithCaptor() {
            auditService.log("user-2", "VIEW_DASHBOARD", "/api/dashboard", "SUCCESS", "10.0.0.1", "overview");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());

            AuditLog saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo("user-2");
            assertThat(saved.getAction()).isEqualTo("VIEW_DASHBOARD");
            assertThat(saved.getResource()).isEqualTo("/api/dashboard");
            assertThat(saved.getStatus()).isEqualTo("SUCCESS");
            assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
            assertThat(saved.getDetails()).isEqualTo("overview");
            assertThat(saved.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("userId 為 null — 正常儲存")
        void nullUserIdSaves() {
            auditService.log(null, "LOGIN", "/api/auth/login", "FAILED", "1.2.3.4", "Invalid password");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isNull();
        }

        @Test
        @DisplayName("repository 拋例外 — 不拋出（try-catch 保護）")
        void repositoryExceptionCaught() {
            when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            assertThatCode(() ->
                    auditService.log("user-1", "LOGIN", "/api", "SUCCESS", "1.1.1.1", "")
            ).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("便捷方法")
    class ConvenienceMethodTests {

        @Test
        @DisplayName("logLogin 正確委派")
        void logLoginDelegates() {
            auditService.logLogin("user-3", "/api/auth/login", "SUCCESS", "5.5.5.5");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("LOGIN");
        }

        @Test
        @DisplayName("logLogout 正確委派")
        void logLogoutDelegates() {
            auditService.logLogout("user-4", "6.6.6.6");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("LOGOUT");
        }

        @Test
        @DisplayName("logFailedAuth 正確委派")
        void logFailedAuthDelegates() {
            auditService.logFailedAuth("test@example.com", "7.7.7.7", "bad password");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            AuditLog saved = captor.getValue();
            assertThat(saved.getAction()).isEqualTo("LOGIN");
            assertThat(saved.getStatus()).isEqualTo("FAILED");
            assertThat(saved.getDetails()).contains("test@example.com");
            assertThat(saved.getDetails()).contains("bad password");
        }
    }
}
