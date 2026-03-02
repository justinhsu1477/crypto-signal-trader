package com.trader.shared.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.repository.AuditLogRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuditLogCleanupTask 單元測試
 *
 * 覆蓋：
 * - 正常清理 → 呼叫 repository.deleteByTimestampBefore
 * - cutoff 日期正確（60 天前）
 * - repository 拋例外 → 不拋出（try-catch 保護）
 */
class AuditLogCleanupTaskTest {

    private AuditLogRepository auditLogRepository;
    private AuditLogCleanupTask cleanupTask;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        cleanupTask = new AuditLogCleanupTask(auditLogRepository);
    }

    @Nested
    @DisplayName("cleanupOldAuditLogs")
    class CleanupTests {

        @Test
        @DisplayName("正常執行 → 呼叫 deleteByTimestampBefore 並傳入 60 天前的 cutoff")
        void normalCleanup() {
            when(auditLogRepository.deleteByTimestampBefore(any(LocalDateTime.class))).thenReturn(150);

            cleanupTask.cleanupOldAuditLogs();

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(auditLogRepository).deleteByTimestampBefore(captor.capture());

            LocalDateTime cutoff = captor.getValue();
            // cutoff 應該大約在 60 天前（用 AppConstants.ZONE_ID 與程式碼一致，容許 1 分鐘誤差）
            LocalDateTime expected = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(60);
            assertThat(cutoff).isBefore(expected.plusMinutes(1));
            assertThat(cutoff).isAfter(expected.minusMinutes(1));
        }

        @Test
        @DisplayName("刪除 0 筆 → 正常完成不拋例外")
        void zeroDeleted() {
            when(auditLogRepository.deleteByTimestampBefore(any(LocalDateTime.class))).thenReturn(0);

            assertThatCode(() -> cleanupTask.cleanupOldAuditLogs())
                    .doesNotThrowAnyException();

            verify(auditLogRepository).deleteByTimestampBefore(any(LocalDateTime.class));
        }

        @Test
        @DisplayName("repository 拋例外 → 不拋出（try-catch 保護排程不中斷）")
        void repositoryExceptionCaught() {
            when(auditLogRepository.deleteByTimestampBefore(any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThatCode(() -> cleanupTask.cleanupOldAuditLogs())
                    .doesNotThrowAnyException();

            verify(auditLogRepository).deleteByTimestampBefore(any(LocalDateTime.class));
        }

        @Test
        @DisplayName("大量刪除 → 正常完成")
        void largeBatchDeleted() {
            when(auditLogRepository.deleteByTimestampBefore(any(LocalDateTime.class))).thenReturn(10000);

            assertThatCode(() -> cleanupTask.cleanupOldAuditLogs())
                    .doesNotThrowAnyException();

            verify(auditLogRepository).deleteByTimestampBefore(any(LocalDateTime.class));
        }
    }
}
