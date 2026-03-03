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
import com.trader.shared.config.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公告服務
 *
 * 負責公告 CRUD + 發佈推送邏輯。
 *
 * 發佈流程：
 *   1. 更新 DB 狀態為 PUBLISHED
 *   2. WebSocket 推送（在線用戶即時收到）
 *   3. RabbitMQ Fanout Exchange 推送（Discord + LINE consumer 各自消費）
 *
 * 面試重點：
 *   - DB 是「公告欄」（持久化，離線用戶上線後也能看到）
 *   - MQ 是「廣播喇叭」（一次性投遞，consumer 消費後訊息就沒了）
 *   - WebSocket 是「即時通知」（只有在線用戶收得到）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadTrackingRepository readTrackingRepository;
    private final RabbitTemplate rabbitTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    // ===== Admin CRUD =====

    /** 建立草稿 */
    @Transactional
    public Announcement createDraft(String adminId, CreateAnnouncementRequest req) {
        Announcement announcement = Announcement.builder()
                .title(req.getTitle())
                .content(req.getContent())
                .category(parseCategory(req.getCategory()))
                .priority(parsePriority(req.getPriority()))
                .channels(req.getChannels())
                .status(Announcement.Status.DRAFT)
                .createdBy(adminId)
                .build();

        announcement = announcementRepository.save(announcement);
        log.info("公告草稿建立: id={}, title={}, admin={}", announcement.getId(), announcement.getTitle(), adminId);
        return announcement;
    }

    /** 更新草稿（只能更新 DRAFT 狀態） */
    @Transactional
    public Announcement updateDraft(Long id, String adminId, CreateAnnouncementRequest req) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公告不存在: " + id));

        if (announcement.getStatus() != Announcement.Status.DRAFT) {
            throw new IllegalStateException("只能編輯草稿狀態的公告");
        }

        announcement.setTitle(req.getTitle());
        announcement.setContent(req.getContent());
        announcement.setCategory(parseCategory(req.getCategory()));
        announcement.setPriority(parsePriority(req.getPriority()));
        announcement.setChannels(req.getChannels());

        announcement = announcementRepository.save(announcement);
        log.info("公告草稿更新: id={}, admin={}", id, adminId);
        return announcement;
    }

    /**
     * 發佈公告 → DB commit 後才推送 WebSocket + RabbitMQ
     *
     * 面試重點：為什麼用 afterCommit？
     *   問題：如果推送包在 @Transactional 裡，訊息發出時 DB 還沒 COMMIT
     *         → Consumer 查 DB 可能讀不到 / 讀到舊狀態
     *         → 若 TX rollback，已送出的訊息變「幽靈訊息」
     *         → 推送期間佔住 DB connection，浪費 connection pool
     *   解法：TransactionSynchronization.afterCommit()
     *         → 確保 DB 已持久化，再發訊息
     *         → Consumer 一定能讀到最新狀態
     *         → DB connection 已歸還 pool
     */
    @Transactional
    public Announcement publish(Long id, String adminId) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公告不存在: " + id));

        if (announcement.getStatus() == Announcement.Status.ARCHIVED) {
            throw new IllegalStateException("已封存的公告不能發佈");
        }

        announcement.setStatus(Announcement.Status.PUBLISHED);
        announcement.setPublishedAt(LocalDateTime.now(AppConstants.ZONE_ID));
        announcement = announcementRepository.save(announcement);

        log.info("公告已發佈: id={}, title={}, channels={}, admin={}",
                announcement.getId(), announcement.getTitle(), announcement.getChannels(), adminId);

        // 註冊 afterCommit callback：TX commit 後才推送，避免幽靈訊息
        final Announcement saved = announcement;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushToChannels(saved);
            }
        });

        return announcement;
    }

    /** 封存公告 */
    @Transactional
    public void archive(Long id, String adminId) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公告不存在: " + id));

        announcement.setStatus(Announcement.Status.ARCHIVED);
        announcementRepository.save(announcement);
        log.info("公告已封存: id={}, admin={}", id, adminId);
    }

    /** 刪除草稿（只能刪 DRAFT） */
    @Transactional
    public void deleteDraft(Long id, String adminId) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公告不存在: " + id));

        if (announcement.getStatus() != Announcement.Status.DRAFT) {
            throw new IllegalStateException("只能刪除草稿狀態的公告");
        }

        announcementRepository.delete(announcement);
        log.info("公告草稿已刪除: id={}, admin={}", id, adminId);
    }

    /**
     * Admin: 取得全部公告（含草稿）+ 已讀統計
     *
     * 修復 N+1 問題：原本 stream().map() 內逐筆呼叫 countByAnnouncementId()（N+1 查詢），
     * 改為 countReadPerAnnouncement() 一次 GROUP BY 取回所有公告的 readCount（2 查詢）。
     */
    public List<AnnouncementResponse> getAllForAdmin() {
        List<Announcement> announcements = announcementRepository.findAllByOrderByCreatedAtDesc();

        // 批次查詢：一次取回所有公告的已讀人數（取代 N 次 countByAnnouncementId）
        Map<Long, Long> readCountMap = readTrackingRepository.countReadPerAnnouncement().stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        return announcements.stream()
                .map(a -> {
                    AnnouncementResponse resp = AnnouncementResponse.from(a);
                    resp.setReadCount(readCountMap.getOrDefault(a.getId(), 0L));
                    return resp;
                })
                .toList();
    }

    // ===== User 查詢 =====

    /** User: 已發佈公告（分頁 + 已讀狀態） */
    public AnnouncementListResponse getPublishedForUser(String userId, int page, int size) {
        Page<Announcement> pageResult = announcementRepository
                .findByStatusOrderByPublishedAtDesc(Announcement.Status.PUBLISHED, PageRequest.of(page, size));

        Set<Long> readIds = readTrackingRepository.findReadAnnouncementIdsByUserId(userId);

        List<AnnouncementResponse> responses = pageResult.getContent().stream()
                .map(a -> {
                    AnnouncementResponse resp = AnnouncementResponse.from(a);
                    resp.setIsRead(readIds.contains(a.getId()));
                    return resp;
                })
                .toList();

        long publishedCount = announcementRepository.countByStatus(Announcement.Status.PUBLISHED);
        long unreadCount = publishedCount - readIds.size();
        if (unreadCount < 0) unreadCount = 0;

        return AnnouncementListResponse.builder()
                .announcements(responses)
                .totalElements((int) pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .currentPage(page)
                .unreadCount(unreadCount)
                .build();
    }

    /** User: 標記已讀 */
    @Transactional
    public void markAsRead(Long announcementId, String userId) {
        if (readTrackingRepository.existsByAnnouncementIdAndUserId(announcementId, userId)) {
            return; // 已標記過
        }

        readTrackingRepository.save(AnnouncementReadTracking.builder()
                .announcementId(announcementId)
                .userId(userId)
                .build());
    }

    /** User: 未讀數量 */
    public long getUnreadCount(String userId) {
        long publishedCount = announcementRepository.countByStatus(Announcement.Status.PUBLISHED);
        long readCount = readTrackingRepository.findReadAnnouncementIdsByUserId(userId).size();
        return Math.max(0, publishedCount - readCount);
    }

    // ===== 推送邏輯 =====

    /**
     * 推送公告到 WebSocket + RabbitMQ Fanout
     *
     * 面試重點：
     *   - SimpMessagingTemplate.convertAndSend → 在線 Web 用戶即時收到
     *   - RabbitTemplate.convertAndSend(fanoutExchange, "", msg) → Fanout 忽略 routing-key
     *   - 各 consumer 獨立消費（Discord / LINE 互不影響）
     */
    private void pushToChannels(Announcement announcement) {
        AnnouncementMessage msg = AnnouncementMessage.builder()
                .announcementId(announcement.getId())
                .title(announcement.getTitle())
                .content(announcement.getContent())
                .category(announcement.getCategory().name())
                .priority(announcement.getPriority().name())
                .channels(announcement.getChannels())
                .publishedAt(announcement.getPublishedAt())
                .createdBy(announcement.getCreatedBy())
                .build();

        // 1. WebSocket 即時推送（在線用戶）
        try {
            messagingTemplate.convertAndSend("/topic/announcements", msg);
            log.debug("WebSocket 公告推送成功: id={}", announcement.getId());
        } catch (Exception e) {
            log.warn("WebSocket 公告推送失敗（非致命）: id={}, error={}", announcement.getId(), e.getMessage());
        }

        // 2. RabbitMQ Fanout → Discord + LINE consumer
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.ANNOUNCEMENT_EXCHANGE, "", msg);
            log.info("RabbitMQ 公告推送成功: id={}, exchange={}", announcement.getId(), RabbitMQConfig.ANNOUNCEMENT_EXCHANGE);
        } catch (Exception e) {
            log.error("RabbitMQ 公告推送失敗: id={}", announcement.getId(), e);
        }
    }

    // ===== 工具方法 =====

    private Announcement.Category parseCategory(String category) {
        try {
            return Announcement.Category.valueOf(category);
        } catch (Exception e) {
            return Announcement.Category.GENERAL;
        }
    }

    private Announcement.Priority parsePriority(String priority) {
        try {
            return Announcement.Priority.valueOf(priority);
        } catch (Exception e) {
            return Announcement.Priority.NORMAL;
        }
    }
}
