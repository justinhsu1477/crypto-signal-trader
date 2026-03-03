package com.trader.notification.repository;

import com.trader.notification.entity.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Admin: 全部公告依建立時間倒序 */
    List<Announcement> findAllByOrderByCreatedAtDesc();

    /** User: 已發佈公告依發佈時間倒序（分頁） */
    Page<Announcement> findByStatusOrderByPublishedAtDesc(Announcement.Status status, Pageable pageable);

    /** User: 已發佈公告總數 */
    long countByStatus(Announcement.Status status);
}
