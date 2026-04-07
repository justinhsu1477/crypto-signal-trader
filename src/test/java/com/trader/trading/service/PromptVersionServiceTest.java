package com.trader.trading.service;

import com.trader.trading.entity.PromptVersion;
import com.trader.trading.repository.PromptVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PromptVersionService 單元測試
 *
 * 覆蓋：
 * - 建立版本（自動遞增版本號 + token 估算）
 * - 啟用版本（deactivateAll + activate + gRPC 推送）
 * - 啟用不存在版本（拋出 IllegalArgumentException）
 * - 取得 active prompt
 * - 列出所有版本（倒序）
 * - 啟動同步（@PostConstruct — 有/無 active prompt）
 */
@ExtendWith(MockitoExtension.class)
class PromptVersionServiceTest {

    @Mock
    private PromptVersionRepository promptVersionRepository;

    @Mock
    private MonitorConfigStore monitorConfigStore;

    @InjectMocks
    private PromptVersionService service;

    // ==================== 建立版本 ====================

    @Nested
    @DisplayName("建立版本")
    class CreateVersionTests {

        @Test
        @DisplayName("自動遞增版本號 — maxVersion + 1")
        void createVersion_autoIncrementsVersion() {
            // Arrange
            when(promptVersionRepository.findMaxVersion()).thenReturn(3);
            when(promptVersionRepository.save(any(PromptVersion.class))).thenAnswer(inv -> {
                PromptVersion v = inv.getArgument(0);
                v.setId(10L);
                return v;
            });

            // Act
            PromptVersion result = service.createVersion("你是一個交易分析師", "第四版 prompt");

            // Assert
            ArgumentCaptor<PromptVersion> captor = ArgumentCaptor.forClass(PromptVersion.class);
            verify(promptVersionRepository).save(captor.capture());

            PromptVersion saved = captor.getValue();
            assertThat(saved.getVersion()).isEqualTo(4);
            assertThat(saved.getContent()).isEqualTo("你是一個交易分析師");
            assertThat(saved.getDescription()).isEqualTo("第四版 prompt");
            assertThat(saved.isActive()).isFalse();
            assertThat(result.getId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("首次建立 — maxVersion=0 時版本號為 1")
        void createVersion_firstVersion_startsAtOne() {
            when(promptVersionRepository.findMaxVersion()).thenReturn(0);
            when(promptVersionRepository.save(any(PromptVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            PromptVersion result = service.createVersion("initial prompt", "初始版本");

            assertThat(result.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("token 估算 — 透過 createVersion 間接驗證 estimateTokenCount")
        void createVersion_estimatesTokenCount() {
            // "Hello" = 5 chars → 5/3 + 1 = 2
            when(promptVersionRepository.findMaxVersion()).thenReturn(0);
            when(promptVersionRepository.save(any(PromptVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            PromptVersion result = service.createVersion("Hello", "test");

            assertThat(result.getTokenCount()).isEqualTo(2); // 5/3 + 1

            // 空字串 → 0/3 + 1 = 1
            PromptVersion result2 = service.createVersion("", "empty");
            assertThat(result2.getTokenCount()).isEqualTo(1); // 0/3 + 1
        }
    }

    // ==================== 啟用版本 ====================

    @Nested
    @DisplayName("啟用版本")
    class ActivateVersionTests {

        @Test
        @DisplayName("啟用成功 — deactivateAll + 設定 active + gRPC 推送")
        void activateVersion_deactivatesAllThenActivates() {
            // Arrange
            PromptVersion target = PromptVersion.builder()
                    .id(5L).version(3).content("v3 prompt").description("第三版").active(false).build();
            when(promptVersionRepository.findById(5L)).thenReturn(Optional.of(target));
            when(promptVersionRepository.save(any(PromptVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            PromptVersion result = service.activateVersion(5L);

            // Assert
            verify(promptVersionRepository).deactivateAll();
            assertThat(result.isActive()).isTrue();
            verify(promptVersionRepository).save(target);
            verify(monitorConfigStore).updatePrompt("v3 prompt", 3);
        }

        @Test
        @DisplayName("版本不存在 — 拋出 IllegalArgumentException")
        void activateVersion_notFound_throwsException() {
            when(promptVersionRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activateVersion(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");

            verify(promptVersionRepository, never()).deactivateAll();
            verify(monitorConfigStore, never()).updatePrompt(anyString(), anyInt());
        }
    }

    // ==================== 查詢 ====================

    @Nested
    @DisplayName("查詢")
    class QueryTests {

        @Test
        @DisplayName("取得 active prompt — 回傳 active 版本")
        void getActivePrompt_returnsActiveVersion() {
            PromptVersion active = PromptVersion.builder()
                    .id(2L).version(2).content("active prompt").active(true).build();
            when(promptVersionRepository.findByActiveTrue()).thenReturn(Optional.of(active));

            Optional<PromptVersion> result = service.getActivePrompt();

            assertThat(result).isPresent();
            assertThat(result.get().getVersion()).isEqualTo(2);
            assertThat(result.get().isActive()).isTrue();
        }

        @Test
        @DisplayName("列出所有版本 — 回傳倒序排列")
        void getAllVersions_returnsOrderedList() {
            List<PromptVersion> versions = List.of(
                    PromptVersion.builder().id(3L).version(3).build(),
                    PromptVersion.builder().id(2L).version(2).build(),
                    PromptVersion.builder().id(1L).version(1).build()
            );
            when(promptVersionRepository.findAllByOrderByVersionDesc()).thenReturn(versions);

            List<PromptVersion> result = service.getAllVersions();

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getVersion()).isEqualTo(3);
            assertThat(result.get(2).getVersion()).isEqualTo(1);
            verify(promptVersionRepository).findAllByOrderByVersionDesc();
        }
    }

    // ==================== 啟動同步 ====================

    @Nested
    @DisplayName("啟動同步 (@PostConstruct)")
    class SyncOnStartupTests {

        @Test
        @DisplayName("有 active prompt — 推送到 MonitorConfigStore")
        void syncOnStartup_withActivePrompt_pushesToMonitor() {
            PromptVersion active = PromptVersion.builder()
                    .id(1L).version(2).content("startup prompt").active(true).build();
            when(promptVersionRepository.findByActiveTrue()).thenReturn(Optional.of(active));

            service.syncOnStartup();

            verify(monitorConfigStore).updatePrompt("startup prompt", 2);
        }

        @Test
        @DisplayName("無 active prompt — 不推送")
        void syncOnStartup_noActivePrompt_doesNotPush() {
            when(promptVersionRepository.findByActiveTrue()).thenReturn(Optional.empty());

            service.syncOnStartup();

            verify(monitorConfigStore, never()).updatePrompt(anyString(), anyInt());
        }
    }
}
