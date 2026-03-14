package com.trader.shared.controller;

import com.trader.shared.entity.ChangelogEntry;
import com.trader.shared.repository.ChangelogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChangelogControllerTest {

    private ChangelogRepository changelogRepository;
    private ChangelogController controller;

    @BeforeEach
    void setUp() {
        changelogRepository = mock(ChangelogRepository.class);
        controller = new ChangelogController(changelogRepository);
    }

    @Test
    @DisplayName("GET /api/changelog → 只回傳已發佈的")
    void getPublished() {
        ChangelogEntry entry = ChangelogEntry.builder()
                .id(1L).version("1.0.0").title("First").published(true)
                .build();
        when(changelogRepository.findByPublishedTrueOrderByPublishedAtDesc())
                .thenReturn(List.of(entry));

        ResponseEntity<List<ChangelogEntry>> resp = controller.getPublishedChangelogs();

        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).getVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("GET /api/admin/changelog → 回傳所有（含未發佈）")
    void getAll() {
        when(changelogRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(
                        ChangelogEntry.builder().id(1L).published(true).build(),
                        ChangelogEntry.builder().id(2L).published(false).build()
                ));

        ResponseEntity<List<ChangelogEntry>> resp = controller.getAllChangelogs();

        assertThat(resp.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("POST /api/admin/changelog → 新增成功")
    void create() {
        ChangelogEntry input = ChangelogEntry.builder()
                .version("1.1.0").title("New Feature").content("Details")
                .category("FEATURE").build();
        ChangelogEntry saved = ChangelogEntry.builder()
                .id(1L).version("1.1.0").title("New Feature").content("Details")
                .category("FEATURE").build();

        when(changelogRepository.save(any())).thenReturn(saved);

        ResponseEntity<ChangelogEntry> resp = controller.createChangelog(input);

        assertThat(resp.getBody().getId()).isEqualTo(1L);
        verify(changelogRepository).save(input);
    }

    @Test
    @DisplayName("PUT /api/admin/changelog/{id} → 更新成功")
    void update() {
        ChangelogEntry existing = ChangelogEntry.builder()
                .id(1L).version("1.0.0").title("Old").content("Old content")
                .category("UPDATE").build();
        when(changelogRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(changelogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChangelogEntry update = ChangelogEntry.builder()
                .title("Updated Title").build();

        ResponseEntity<ChangelogEntry> resp = controller.updateChangelog(1L, update);

        assertThat(resp.getBody().getTitle()).isEqualTo("Updated Title");
        assertThat(resp.getBody().getVersion()).isEqualTo("1.0.0"); // 未改的欄位保留
    }

    @Test
    @DisplayName("PUT /api/admin/changelog/{id} → 不存在回 404")
    void updateNotFound() {
        when(changelogRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ChangelogEntry> resp = controller.updateChangelog(99L,
                ChangelogEntry.builder().title("x").build());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /api/admin/changelog/{id}/publish → 發佈成功")
    void publish() {
        ChangelogEntry entry = ChangelogEntry.builder()
                .id(1L).version("1.0.0").published(false).build();
        when(changelogRepository.findById(1L)).thenReturn(Optional.of(entry));
        when(changelogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ChangelogEntry> resp = controller.publishChangelog(1L);

        assertThat(resp.getBody().isPublished()).isTrue();
        assertThat(resp.getBody().getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("DELETE /api/admin/changelog/{id} → 刪除成功")
    void delete() {
        when(changelogRepository.existsById(1L)).thenReturn(true);

        ResponseEntity<Map<String, String>> resp = controller.deleteChangelog(1L);

        assertThat(resp.getBody().get("message")).isEqualTo("已刪除");
        verify(changelogRepository).deleteById(1L);
    }

    @Test
    @DisplayName("DELETE /api/admin/changelog/{id} → 不存在回 404")
    void deleteNotFound() {
        when(changelogRepository.existsById(99L)).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = controller.deleteChangelog(99L);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
