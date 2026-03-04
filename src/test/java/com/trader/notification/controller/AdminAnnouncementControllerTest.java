package com.trader.notification.controller;

import com.trader.notification.dto.AnnouncementResponse;
import com.trader.notification.dto.CreateAnnouncementRequest;
import com.trader.notification.entity.Announcement;
import com.trader.notification.service.AnnouncementService;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminAnnouncementController 單元測試
 *
 * 覆蓋：列表排序、建立草稿、發佈、封存、刪除
 */
class AdminAnnouncementControllerTest {

    private AnnouncementService announcementService;
    private AuditService auditService;
    private AdminAnnouncementController controller;

    @BeforeEach
    void setUp() {
        announcementService = mock(AnnouncementService.class);
        auditService = mock(AuditService.class);
        controller = new AdminAnnouncementController(announcementService, auditService);
    }

    // ==================== 列表排序 ====================

    @Nested
    @DisplayName("列表排序")
    class ListTests {

        @Test
        @DisplayName("預設排序（createdAt desc）→ 最新的在前")
        void defaultSortCreatedAtDesc() {
            AnnouncementResponse older = AnnouncementResponse.builder()
                    .id(1L).title("Older").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                    .readCount(5L).build();
            AnnouncementResponse newer = AnnouncementResponse.builder()
                    .id(2L).title("Newer").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 3, 10, 0))
                    .readCount(3L).build();

            when(announcementService.getAllForAdmin()).thenReturn(List.of(older, newer));

            ResponseEntity<List<AnnouncementResponse>> response = controller.list("createdAt", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AnnouncementResponse> body = response.getBody();
            assertThat(body).hasSize(2);
            assertThat(body.get(0).getId()).isEqualTo(2L); // newer first
            assertThat(body.get(1).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("sort by title asc → 字母順序")
        void sortByTitleAsc() {
            AnnouncementResponse banana = AnnouncementResponse.builder()
                    .id(1L).title("Banana Update").category("UPDATE").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                    .readCount(0L).build();
            AnnouncementResponse apple = AnnouncementResponse.builder()
                    .id(2L).title("Apple Notice").category("GENERAL").priority("HIGH")
                    .status("DRAFT").createdAt(LocalDateTime.of(2026, 3, 2, 10, 0))
                    .readCount(0L).build();

            when(announcementService.getAllForAdmin()).thenReturn(List.of(banana, apple));

            ResponseEntity<List<AnnouncementResponse>> response = controller.list("title", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AnnouncementResponse> body = response.getBody();
            assertThat(body.get(0).getTitle()).isEqualTo("Apple Notice");
            assertThat(body.get(1).getTitle()).isEqualTo("Banana Update");
        }

        @Test
        @DisplayName("sort by readCount desc → 最多閱讀的在前")
        void sortByReadCountDesc() {
            AnnouncementResponse low = AnnouncementResponse.builder()
                    .id(1L).title("Low Read").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                    .readCount(2L).build();
            AnnouncementResponse high = AnnouncementResponse.builder()
                    .id(2L).title("High Read").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 2, 10, 0))
                    .readCount(100L).build();
            AnnouncementResponse mid = AnnouncementResponse.builder()
                    .id(3L).title("Mid Read").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 3, 10, 0))
                    .readCount(50L).build();

            when(announcementService.getAllForAdmin()).thenReturn(List.of(low, high, mid));

            ResponseEntity<List<AnnouncementResponse>> response = controller.list("readCount", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AnnouncementResponse> body = response.getBody();
            assertThat(body.get(0).getReadCount()).isEqualTo(100L);
            assertThat(body.get(1).getReadCount()).isEqualTo(50L);
            assertThat(body.get(2).getReadCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("publishedAt 排序 → null 永遠排最後")
        void sortByPublishedAtNullLast() {
            AnnouncementResponse published = AnnouncementResponse.builder()
                    .id(1L).title("Published").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                    .publishedAt(LocalDateTime.of(2026, 3, 2, 10, 0)).readCount(5L).build();
            AnnouncementResponse draft = AnnouncementResponse.builder()
                    .id(2L).title("Draft").category("GENERAL").priority("NORMAL")
                    .status("DRAFT").createdAt(LocalDateTime.of(2026, 3, 3, 10, 0))
                    .publishedAt(null).readCount(0L).build();
            AnnouncementResponse earlierPublished = AnnouncementResponse.builder()
                    .id(3L).title("Earlier Published").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 2, 28, 10, 0))
                    .publishedAt(LocalDateTime.of(2026, 3, 1, 10, 0)).readCount(10L).build();

            when(announcementService.getAllForAdmin()).thenReturn(List.of(published, draft, earlierPublished));

            // desc → 最新發佈在前，但 null 永遠排最後
            ResponseEntity<List<AnnouncementResponse>> response = controller.list("publishedAt", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AnnouncementResponse> body = response.getBody();
            assertThat(body.get(0).getId()).isEqualTo(1L); // 2026-03-02
            assertThat(body.get(1).getId()).isEqualTo(3L); // 2026-03-01
            assertThat(body.get(2).getId()).isEqualTo(2L); // null → last
        }

        @Test
        @DisplayName("未知 sortBy → fallback 到 createdAt")
        void unknownSortByFallbackToCreatedAt() {
            AnnouncementResponse older = AnnouncementResponse.builder()
                    .id(1L).title("Older").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                    .readCount(0L).build();
            AnnouncementResponse newer = AnnouncementResponse.builder()
                    .id(2L).title("Newer").category("GENERAL").priority("NORMAL")
                    .status("PUBLISHED").createdAt(LocalDateTime.of(2026, 3, 3, 10, 0))
                    .readCount(0L).build();

            when(announcementService.getAllForAdmin()).thenReturn(List.of(older, newer));

            ResponseEntity<List<AnnouncementResponse>> response = controller.list("nonExistentField", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AnnouncementResponse> body = response.getBody();
            assertThat(body.get(0).getId()).isEqualTo(2L); // newer first (createdAt desc)
            assertThat(body.get(1).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("空列表 → 200 + 空陣列")
        void emptyListReturns200() {
            when(announcementService.getAllForAdmin()).thenReturn(List.of());

            ResponseEntity<List<AnnouncementResponse>> response = controller.list("createdAt", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ==================== 建立草稿 ====================

    @Nested
    @DisplayName("建立草稿")
    class CreateTests {

        @Test
        @DisplayName("成功建立 → 200 + AnnouncementResponse")
        void createSuccess() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                CreateAnnouncementRequest request = CreateAnnouncementRequest.builder()
                        .title("New Announcement")
                        .content("Some content")
                        .category("GENERAL")
                        .priority("NORMAL")
                        .channels("DISCORD,LINE")
                        .build();

                Announcement saved = Announcement.builder()
                        .id(1L).title("New Announcement").content("Some content")
                        .category(Announcement.Category.GENERAL).priority(Announcement.Priority.NORMAL)
                        .channels("DISCORD,LINE").status(Announcement.Status.DRAFT)
                        .createdBy("admin1").createdAt(LocalDateTime.of(2026, 3, 4, 10, 0))
                        .build();

                when(announcementService.createDraft(eq("admin1"), any(CreateAnnouncementRequest.class)))
                        .thenReturn(saved);

                ResponseEntity<?> response = controller.create(request);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isInstanceOf(AnnouncementResponse.class);
                AnnouncementResponse body = (AnnouncementResponse) response.getBody();
                assertThat(body.getTitle()).isEqualTo("New Announcement");
                assertThat(body.getStatus()).isEqualTo("DRAFT");

                verify(auditService).log(eq("admin1"), eq("CREATE_ANNOUNCEMENT"),
                        eq("/api/admin/announcements"), eq("SUCCESS"), eq(""), contains("New Announcement"));
            }
        }

        @Test
        @DisplayName("Service 拋出例外 → 400")
        void createServiceThrows400() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                CreateAnnouncementRequest request = CreateAnnouncementRequest.builder()
                        .title("Bad Request")
                        .content("Content")
                        .build();

                when(announcementService.createDraft(eq("admin1"), any(CreateAnnouncementRequest.class)))
                        .thenThrow(new RuntimeException("建立失敗"));

                ResponseEntity<?> response = controller.create(request);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                verifyNoInteractions(auditService);
            }
        }
    }

    // ==================== 發佈公告 ====================

    @Nested
    @DisplayName("發佈公告")
    class PublishTests {

        @Test
        @DisplayName("成功發佈 → 200 + 包含 message 和 announcement")
        void publishSuccess() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                Announcement published = Announcement.builder()
                        .id(1L).title("Published Announcement").content("Content")
                        .category(Announcement.Category.GENERAL).priority(Announcement.Priority.NORMAL)
                        .channels("ALL").status(Announcement.Status.PUBLISHED)
                        .createdBy("admin1").createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                        .publishedAt(LocalDateTime.of(2026, 3, 4, 10, 0))
                        .build();

                when(announcementService.publish(1L, "admin1")).thenReturn(published);

                ResponseEntity<?> response = controller.publish(1L);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body).containsKey("message");
                assertThat(body).containsKey("announcement");

                verify(auditService).log(eq("admin1"), eq("PUBLISH_ANNOUNCEMENT"),
                        contains("/publish"), eq("SUCCESS"), eq(""), anyString());
            }
        }

        @Test
        @DisplayName("公告不存在 → 404")
        void publishNotFound404() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                when(announcementService.publish(999L, "admin1"))
                        .thenThrow(new IllegalArgumentException("公告不存在"));

                ResponseEntity<?> response = controller.publish(999L);

                assertThat(response.getStatusCode().value()).isEqualTo(404);
                verifyNoInteractions(auditService);
            }
        }

        @Test
        @DisplayName("非草稿狀態 → 400")
        void publishInvalidState400() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                when(announcementService.publish(1L, "admin1"))
                        .thenThrow(new IllegalStateException("只有草稿可以發佈"));

                ResponseEntity<?> response = controller.publish(1L);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                verifyNoInteractions(auditService);
            }
        }
    }

    // ==================== 封存公告 ====================

    @Nested
    @DisplayName("封存公告")
    class ArchiveTests {

        @Test
        @DisplayName("成功封存 → 200")
        void archiveSuccess() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                doNothing().when(announcementService).archive(1L, "admin1");

                ResponseEntity<?> response = controller.archive(1L);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body).containsEntry("message", "公告已封存");

                verify(auditService).log(eq("admin1"), eq("ARCHIVE_ANNOUNCEMENT"),
                        contains("/archive"), eq("SUCCESS"), eq(""), eq(""));
            }
        }

        @Test
        @DisplayName("公告不存在 → 404")
        void archiveNotFound404() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                doThrow(new IllegalArgumentException("公告不存在"))
                        .when(announcementService).archive(999L, "admin1");

                ResponseEntity<?> response = controller.archive(999L);

                assertThat(response.getStatusCode().value()).isEqualTo(404);
                verifyNoInteractions(auditService);
            }
        }
    }

    // ==================== 刪除草稿 ====================

    @Nested
    @DisplayName("刪除草稿")
    class DeleteTests {

        @Test
        @DisplayName("成功刪除 → 200")
        void deleteSuccess() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                doNothing().when(announcementService).deleteDraft(1L, "admin1");

                ResponseEntity<?> response = controller.delete(1L);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body).containsEntry("message", "草稿已刪除");

                verify(auditService).log(eq("admin1"), eq("DELETE_ANNOUNCEMENT"),
                        contains("/api/admin/announcements/1"), eq("SUCCESS"), eq(""), eq(""));
            }
        }

        @Test
        @DisplayName("公告不存在 → 404")
        void deleteNotFound404() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                doThrow(new IllegalArgumentException("公告不存在"))
                        .when(announcementService).deleteDraft(999L, "admin1");

                ResponseEntity<?> response = controller.delete(999L);

                assertThat(response.getStatusCode().value()).isEqualTo(404);
                verifyNoInteractions(auditService);
            }
        }

        @Test
        @DisplayName("非草稿狀態 → 400")
        void deleteNotDraft400() {
            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                doThrow(new IllegalStateException("只有草稿可以刪除"))
                        .when(announcementService).deleteDraft(1L, "admin1");

                ResponseEntity<?> response = controller.delete(1L);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                verifyNoInteractions(auditService);
            }
        }
    }
}
