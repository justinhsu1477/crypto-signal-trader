package com.trader.notification.service;

import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.dto.AnnouncementListResponse;
import com.trader.notification.dto.AnnouncementResponse;
import com.trader.notification.dto.CreateAnnouncementRequest;
import com.trader.notification.entity.Announcement;
import com.trader.notification.entity.AnnouncementReadTracking;
import com.trader.notification.model.AnnouncementMessage;
import com.trader.notification.repository.AnnouncementReadTrackingRepository;
import com.trader.notification.repository.AnnouncementRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AnnouncementService 單元測試
 *
 * 覆蓋：CRUD 生命週期、發佈推送邏輯（afterCommit）、已讀追蹤、未讀計數
 */
class AnnouncementServiceTest {

    private AnnouncementRepository announcementRepository;
    private AnnouncementReadTrackingRepository readTrackingRepository;
    private RabbitTemplate rabbitTemplate;
    private SimpMessagingTemplate messagingTemplate;
    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        readTrackingRepository = mock(AnnouncementReadTrackingRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        service = new AnnouncementService(
                announcementRepository,
                readTrackingRepository,
                rabbitTemplate,
                messagingTemplate
        );
    }

    // ==================== createDraft ====================

    @Nested
    @DisplayName("createDraft — 建立草稿")
    class CreateDraftTests {

        @Test
        @DisplayName("正常建立 — 存入 DB 狀態為 DRAFT")
        void createsDraftSuccessfully() {
            CreateAnnouncementRequest req = CreateAnnouncementRequest.builder()
                    .title("系統維護通知")
                    .content("將於今晚進行維護")
                    .category("MAINTENANCE")
                    .priority("HIGH")
                    .channels("ALL")
                    .build();

            when(announcementRepository.save(any(Announcement.class)))
                    .thenAnswer(inv -> {
                        Announcement a = inv.getArgument(0);
                        a.setId(1L);
                        return a;
                    });

            Announcement result = service.createDraft("admin-1", req);

            assertThat(result.getTitle()).isEqualTo("系統維護通知");
            assertThat(result.getStatus()).isEqualTo(Announcement.Status.DRAFT);
            assertThat(result.getCategory()).isEqualTo(Announcement.Category.MAINTENANCE);
            assertThat(result.getCreatedBy()).isEqualTo("admin-1");

            verify(announcementRepository).save(any(Announcement.class));
        }

        @Test
        @DisplayName("無效 category — 預設 GENERAL")
        void invalidCategoryDefaultsToGeneral() {
            CreateAnnouncementRequest req = CreateAnnouncementRequest.builder()
                    .title("Test")
                    .content("Content")
                    .category("INVALID")
                    .priority("NORMAL")
                    .channels("ALL")
                    .build();

            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Announcement result = service.createDraft("admin-1", req);

            assertThat(result.getCategory()).isEqualTo(Announcement.Category.GENERAL);
        }
    }

    // ==================== updateDraft ====================

    @Nested
    @DisplayName("updateDraft — 更新草稿")
    class UpdateDraftTests {

        @Test
        @DisplayName("DRAFT 狀態 — 正常更新")
        void updatesWhenDraft() {
            Announcement existing = Announcement.builder()
                    .id(1L).title("Old").content("Old content")
                    .status(Announcement.Status.DRAFT).createdBy("admin-1")
                    .build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CreateAnnouncementRequest req = CreateAnnouncementRequest.builder()
                    .title("New Title").content("New Content")
                    .category("UPDATE").priority("HIGH").channels("DISCORD")
                    .build();

            Announcement result = service.updateDraft(1L, "admin-1", req);

            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getChannels()).isEqualTo("DISCORD");
        }

        @Test
        @DisplayName("PUBLISHED 狀態 — 拋出 IllegalStateException")
        void rejectsUpdateWhenPublished() {
            Announcement existing = Announcement.builder()
                    .id(1L).status(Announcement.Status.PUBLISHED).build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(existing));

            CreateAnnouncementRequest req = CreateAnnouncementRequest.builder()
                    .title("New").content("Content").build();

            assertThatThrownBy(() -> service.updateDraft(1L, "admin-1", req))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("不存在 — 拋出 IllegalArgumentException")
        void throwsWhenNotFound() {
            when(announcementRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateDraft(99L, "admin-1",
                    CreateAnnouncementRequest.builder().title("T").content("C").build()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== publish ====================

    @Nested
    @DisplayName("publish — 發佈公告（afterCommit 推送）")
    class PublishTests {

        /**
         * 輔助方法：模擬 TransactionSynchronizationManager，
         * 捕獲註冊的 afterCommit callback 並手動觸發。
         *
         * 面試重點：
         *   單元測試沒有真實 Spring TX，所以 registerSynchronization() 會失敗。
         *   用 mockStatic 攔截，捕獲 callback，手動呼叫 afterCommit() 來模擬 TX commit。
         */
        private TransactionSynchronization publishWithAfterCommit(Runnable publishAction) {
            ArgumentCaptor<TransactionSynchronization> syncCaptor =
                    ArgumentCaptor.forClass(TransactionSynchronization.class);

            try (MockedStatic<TransactionSynchronizationManager> mockedTxManager =
                         mockStatic(TransactionSynchronizationManager.class)) {

                publishAction.run();

                mockedTxManager.verify(() ->
                        TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            }

            // 模擬 TX commit → 手動觸發 afterCommit
            TransactionSynchronization sync = syncCaptor.getValue();
            sync.afterCommit();
            return sync;
        }

        @Test
        @DisplayName("DRAFT → PUBLISHED + afterCommit 觸發 WebSocket + RabbitMQ")
        void publishesDraftSuccessfully() {
            Announcement draft = Announcement.builder()
                    .id(1L).title("公告").content("內容")
                    .category(Announcement.Category.GENERAL)
                    .priority(Announcement.Priority.NORMAL)
                    .channels("ALL").status(Announcement.Status.DRAFT)
                    .createdBy("admin-1")
                    .build();

            when(announcementRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            final Announcement[] resultHolder = new Announcement[1];
            publishWithAfterCommit(() -> resultHolder[0] = service.publish(1L, "admin-1"));
            Announcement result = resultHolder[0];

            // 驗證狀態變更（TX 內完成）
            assertThat(result.getStatus()).isEqualTo(Announcement.Status.PUBLISHED);
            assertThat(result.getPublishedAt()).isNotNull();

            // 驗證 WebSocket 推送（afterCommit 後）
            verify(messagingTemplate).convertAndSend(eq("/topic/announcements"), any(AnnouncementMessage.class));

            // 驗證 RabbitMQ Fanout 推送（afterCommit 後）
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.ANNOUNCEMENT_EXCHANGE), eq(""), any(AnnouncementMessage.class));
        }

        @Test
        @DisplayName("RabbitMQ 推送內容正確")
        void rabbitMqMessageContainsCorrectData() {
            Announcement draft = Announcement.builder()
                    .id(5L).title("維護公告").content("系統維護")
                    .category(Announcement.Category.MAINTENANCE)
                    .priority(Announcement.Priority.HIGH)
                    .channels("DISCORD,LINE")
                    .status(Announcement.Status.DRAFT).createdBy("admin-1")
                    .build();

            when(announcementRepository.findById(5L)).thenReturn(Optional.of(draft));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            publishWithAfterCommit(() -> service.publish(5L, "admin-1"));

            ArgumentCaptor<AnnouncementMessage> msgCaptor = ArgumentCaptor.forClass(AnnouncementMessage.class);
            verify(rabbitTemplate).convertAndSend(anyString(), anyString(), msgCaptor.capture());

            AnnouncementMessage msg = msgCaptor.getValue();
            assertThat(msg.getAnnouncementId()).isEqualTo(5L);
            assertThat(msg.getTitle()).isEqualTo("維護公告");
            assertThat(msg.getChannels()).isEqualTo("DISCORD,LINE");
            assertThat(msg.getCategory()).isEqualTo("MAINTENANCE");
        }

        @Test
        @DisplayName("publish 時不直接推送 — 只註冊 afterCommit callback")
        void doesNotPushBeforeCommit() {
            Announcement draft = Announcement.builder()
                    .id(1L).title("Test").content("Content")
                    .category(Announcement.Category.GENERAL)
                    .priority(Announcement.Priority.NORMAL)
                    .channels("ALL").status(Announcement.Status.DRAFT)
                    .createdBy("admin-1").build();

            when(announcementRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            try (MockedStatic<TransactionSynchronizationManager> mockedTxManager =
                         mockStatic(TransactionSynchronizationManager.class)) {

                service.publish(1L, "admin-1");

                // TX 內：不應有任何推送
                verify(messagingTemplate, never()).convertAndSend(anyString(), any(AnnouncementMessage.class));
                verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(AnnouncementMessage.class));

                // 但 callback 已註冊
                mockedTxManager.verify(() ->
                        TransactionSynchronizationManager.registerSynchronization(any(TransactionSynchronization.class)));
            }
        }

        @Test
        @DisplayName("ARCHIVED 狀態 — 拋出 IllegalStateException")
        void rejectsPublishWhenArchived() {
            Announcement archived = Announcement.builder()
                    .id(1L).status(Announcement.Status.ARCHIVED).build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(archived));

            assertThatThrownBy(() -> service.publish(1L, "admin-1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("WebSocket 推送失敗 — 不影響 RabbitMQ 推送（非致命）")
        void webSocketFailureDoesNotBlockRabbitMq() {
            Announcement draft = Announcement.builder()
                    .id(1L).title("Test").content("Content")
                    .category(Announcement.Category.GENERAL)
                    .priority(Announcement.Priority.NORMAL)
                    .channels("ALL").status(Announcement.Status.DRAFT)
                    .createdBy("admin-1").build();

            when(announcementRepository.findById(1L)).thenReturn(Optional.of(draft));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("WS failed"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(AnnouncementMessage.class));

            final Announcement[] resultHolder = new Announcement[1];
            publishWithAfterCommit(() -> resultHolder[0] = service.publish(1L, "admin-1"));

            assertThat(resultHolder[0].getStatus()).isEqualTo(Announcement.Status.PUBLISHED);
            // RabbitMQ 仍應被呼叫
            verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(AnnouncementMessage.class));
        }
    }

    // ==================== archive / deleteDraft ====================

    @Nested
    @DisplayName("archive + deleteDraft — 封存與刪除")
    class ArchiveDeleteTests {

        @Test
        @DisplayName("archive — 更新狀態為 ARCHIVED")
        void archivesSuccessfully() {
            Announcement pub = Announcement.builder()
                    .id(1L).status(Announcement.Status.PUBLISHED).build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(pub));

            service.archive(1L, "admin-1");

            assertThat(pub.getStatus()).isEqualTo(Announcement.Status.ARCHIVED);
            verify(announcementRepository).save(pub);
        }

        @Test
        @DisplayName("deleteDraft — DRAFT 狀態才能刪除")
        void deletesDraftSuccessfully() {
            Announcement draft = Announcement.builder()
                    .id(1L).status(Announcement.Status.DRAFT).build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(draft));

            service.deleteDraft(1L, "admin-1");

            verify(announcementRepository).delete(draft);
        }

        @Test
        @DisplayName("deleteDraft — PUBLISHED 狀態 → 拋出 IllegalStateException")
        void rejectsDeleteWhenPublished() {
            Announcement pub = Announcement.builder()
                    .id(1L).status(Announcement.Status.PUBLISHED).build();
            when(announcementRepository.findById(1L)).thenReturn(Optional.of(pub));

            assertThatThrownBy(() -> service.deleteDraft(1L, "admin-1"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================== 已讀追蹤 ====================

    @Nested
    @DisplayName("markAsRead + getUnreadCount — 已讀追蹤")
    class ReadTrackingTests {

        @Test
        @DisplayName("markAsRead — 首次標記寫入 DB")
        void marksAsReadFirstTime() {
            when(readTrackingRepository.existsByAnnouncementIdAndUserId(1L, "user-1")).thenReturn(false);

            service.markAsRead(1L, "user-1");

            verify(readTrackingRepository).save(any(AnnouncementReadTracking.class));
        }

        @Test
        @DisplayName("markAsRead — 重複標記不寫入")
        void doesNotDuplicateRead() {
            when(readTrackingRepository.existsByAnnouncementIdAndUserId(1L, "user-1")).thenReturn(true);

            service.markAsRead(1L, "user-1");

            verify(readTrackingRepository, never()).save(any());
        }

        @Test
        @DisplayName("getUnreadCount — 正確計算")
        void calculatesUnreadCount() {
            when(announcementRepository.countByStatus(Announcement.Status.PUBLISHED)).thenReturn(10L);
            when(readTrackingRepository.findReadAnnouncementIdsByUserId("user-1")).thenReturn(Set.of(1L, 2L, 3L));

            long count = service.getUnreadCount("user-1");

            assertThat(count).isEqualTo(7);
        }

        @Test
        @DisplayName("getUnreadCount — 不會回傳負數")
        void unreadCountNeverNegative() {
            when(announcementRepository.countByStatus(Announcement.Status.PUBLISHED)).thenReturn(2L);
            when(readTrackingRepository.findReadAnnouncementIdsByUserId("user-1")).thenReturn(Set.of(1L, 2L, 3L, 4L, 5L));

            long count = service.getUnreadCount("user-1");

            assertThat(count).isEqualTo(0);
        }
    }

    // ==================== getPublishedForUser ====================

    @Nested
    @DisplayName("getPublishedForUser — 用戶查詢")
    class UserQueryTests {

        @Test
        @DisplayName("回傳分頁結果 + 已讀狀態")
        void returnsPagedResultsWithReadStatus() {
            Announcement a1 = Announcement.builder().id(1L).title("A1").content("C1")
                    .category(Announcement.Category.GENERAL).priority(Announcement.Priority.NORMAL)
                    .channels("ALL").status(Announcement.Status.PUBLISHED).createdBy("admin-1").build();
            Announcement a2 = Announcement.builder().id(2L).title("A2").content("C2")
                    .category(Announcement.Category.UPDATE).priority(Announcement.Priority.HIGH)
                    .channels("ALL").status(Announcement.Status.PUBLISHED).createdBy("admin-1").build();

            Page<Announcement> page = new PageImpl<>(List.of(a1, a2), PageRequest.of(0, 10), 2);
            when(announcementRepository.findByStatusOrderByPublishedAtDesc(
                    eq(Announcement.Status.PUBLISHED), any(PageRequest.class))).thenReturn(page);
            when(readTrackingRepository.findReadAnnouncementIdsByUserId("user-1")).thenReturn(Set.of(1L));
            when(announcementRepository.countByStatus(Announcement.Status.PUBLISHED)).thenReturn(2L);

            AnnouncementListResponse result = service.getPublishedForUser("user-1", 0, 10);

            assertThat(result.getAnnouncements()).hasSize(2);
            assertThat(result.getAnnouncements().get(0).getIsRead()).isTrue();   // id=1 已讀
            assertThat(result.getAnnouncements().get(1).getIsRead()).isFalse();  // id=2 未讀
            assertThat(result.getUnreadCount()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
        }
    }
}
