package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.trading.repository.DiscordRawMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DiscordRawMessageCleanupTask 單元測試 — 驗證 retention cutoff 計算 + 例外吞掉。
 */
@ExtendWith(MockitoExtension.class)
class DiscordRawMessageCleanupTaskTest {

    @Mock
    private DiscordRawMessageRepository repository;

    @InjectMocks
    private DiscordRawMessageCleanupTask task;

    @Test
    @DisplayName("cleanupExpiredMessages — 呼叫 deleteOlderThan 並用 180 天 cutoff")
    void cleanupExpiredMessages_callsRepoAndLogs() {
        when(repository.deleteOlderThan(any())).thenReturn(42);

        task.cleanupExpiredMessages();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteOlderThan(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        LocalDateTime expectedRoughly = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(180);
        // 允許 5 分鐘容差，避免測試執行時間造成 flake
        long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(cutoff, expectedRoughly));
        assertThat(minutesDiff).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("cleanupExpiredMessages — repo throw 時不會 crash（scheduled task 必須容錯）")
    void cleanupExpiredMessages_swallowsExceptions() {
        when(repository.deleteOlderThan(any())).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> task.cleanupExpiredMessages()).doesNotThrowAnyException();
    }
}
