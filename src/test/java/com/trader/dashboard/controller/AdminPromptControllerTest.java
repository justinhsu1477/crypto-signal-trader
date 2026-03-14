package com.trader.dashboard.controller;

import com.trader.trading.entity.PromptVersion;
import com.trader.trading.service.PromptVersionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminPromptController 單元測試
 *
 * 覆蓋：
 * - 列表所有版本（listVersions）
 * - 建立版本（createVersion）
 * - 啟用版本（activateVersion）
 * - 取得啟用中 Prompt（getActivePrompt）
 * - 各種異常路徑（400 / 404 / 204）
 */
@ExtendWith(MockitoExtension.class)
class AdminPromptControllerTest {

    @Mock
    private PromptVersionService promptVersionService;

    @InjectMocks
    private AdminPromptController controller;

    // ==================== Helper ====================

    private PromptVersion buildPromptVersion(Long id, String content, String description, boolean active) {
        return PromptVersion.builder()
                .id(id)
                .content(content)
                .description(description)
                .active(active)
                .build();
    }

    // ==================== listVersions ====================

    @Nested
    @DisplayName("listVersions - 列表所有版本")
    class ListVersions {

        @Test
        @DisplayName("回傳所有版本列表")
        void returnsAllVersions() {
            List<PromptVersion> versions = List.of(
                    buildPromptVersion(1L, "prompt v1", "初版", false),
                    buildPromptVersion(2L, "prompt v2", "改良版", true)
            );
            when(promptVersionService.getAllVersions()).thenReturn(versions);

            ResponseEntity<List<PromptVersion>> response = controller.listVersions();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getContent()).isEqualTo("prompt v1");
            assertThat(response.getBody().get(1).getContent()).isEqualTo("prompt v2");
            verify(promptVersionService).getAllVersions();
        }

        @Test
        @DisplayName("無版本時回傳空列表")
        void returnsEmptyList() {
            when(promptVersionService.getAllVersions()).thenReturn(List.of());

            ResponseEntity<List<PromptVersion>> response = controller.listVersions();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ==================== createVersion ====================

    @Nested
    @DisplayName("createVersion - 建立版本")
    class CreateVersion {

        @Test
        @DisplayName("建立成功 → 200 + PromptVersion")
        void createSuccess() {
            PromptVersion created = buildPromptVersion(1L, "new prompt", "新版 prompt", false);
            when(promptVersionService.createVersion("new prompt", "新版 prompt")).thenReturn(created);

            Map<String, String> body = Map.of("content", "new prompt", "description", "新版 prompt");
            ResponseEntity<PromptVersion> response = controller.createVersion(body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(1L);
            assertThat(response.getBody().getContent()).isEqualTo("new prompt");
            assertThat(response.getBody().getDescription()).isEqualTo("新版 prompt");
            verify(promptVersionService).createVersion("new prompt", "新版 prompt");
        }

        @Test
        @DisplayName("content 為 null → 400")
        void contentNull_returnsBadRequest() {
            Map<String, String> body = Map.of("description", "描述");

            ResponseEntity<PromptVersion> response = controller.createVersion(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNull();
            verify(promptVersionService, never()).createVersion(any(), any());
        }

        @Test
        @DisplayName("content 為空白 → 400")
        void contentBlank_returnsBadRequest() {
            Map<String, String> body = Map.of("content", "   ", "description", "描述");

            ResponseEntity<PromptVersion> response = controller.createVersion(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNull();
            verify(promptVersionService, never()).createVersion(any(), any());
        }
    }

    // ==================== activateVersion ====================

    @Nested
    @DisplayName("activateVersion - 啟用版本")
    class ActivateVersion {

        @Test
        @DisplayName("啟用成功 → 200 + 啟用的版本")
        void activateSuccess() {
            PromptVersion activated = buildPromptVersion(1L, "prompt v1", "初版", true);
            when(promptVersionService.activateVersion(1L)).thenReturn(activated);

            ResponseEntity<PromptVersion> response = controller.activateVersion(1L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(1L);
            assertThat(response.getBody().isActive()).isTrue();
            verify(promptVersionService).activateVersion(1L);
        }

        @Test
        @DisplayName("版本不存在 → IllegalArgumentException → 404")
        void activateNotFound() {
            when(promptVersionService.activateVersion(999L))
                    .thenThrow(new IllegalArgumentException("Prompt 版本不存在: id=999"));

            ResponseEntity<PromptVersion> response = controller.activateVersion(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    // ==================== getActivePrompt ====================

    @Nested
    @DisplayName("getActivePrompt - 取得啟用中 Prompt")
    class GetActivePrompt {

        @Test
        @DisplayName("有啟用版本 → 200 + PromptVersion")
        void activePromptFound() {
            PromptVersion active = buildPromptVersion(2L, "active prompt", "目前使用中", true);
            when(promptVersionService.getActivePrompt()).thenReturn(Optional.of(active));

            ResponseEntity<PromptVersion> response = controller.getActivePrompt();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(2L);
            assertThat(response.getBody().getContent()).isEqualTo("active prompt");
            assertThat(response.getBody().isActive()).isTrue();
            verify(promptVersionService).getActivePrompt();
        }

        @Test
        @DisplayName("無啟用版本 → 204 No Content")
        void activePromptNotFound() {
            when(promptVersionService.getActivePrompt()).thenReturn(Optional.empty());

            ResponseEntity<PromptVersion> response = controller.getActivePrompt();

            assertThat(response.getStatusCode().value()).isEqualTo(204);
            assertThat(response.getBody()).isNull();
            verify(promptVersionService).getActivePrompt();
        }
    }
}
