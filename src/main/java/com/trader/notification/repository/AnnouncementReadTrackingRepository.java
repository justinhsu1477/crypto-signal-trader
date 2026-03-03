package com.trader.notification.repository;

import com.trader.notification.entity.AnnouncementReadTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface AnnouncementReadTrackingRepository extends JpaRepository<AnnouncementReadTracking, Long> {

    /** 檢查用戶是否已讀特定公告 */
    boolean existsByAnnouncementIdAndUserId(Long announcementId, String userId);

    /** 取得用戶已讀的所有公告 ID */
    @Query("SELECT art.announcementId FROM AnnouncementReadTracking art WHERE art.userId = :userId")
    Set<Long> findReadAnnouncementIdsByUserId(@Param("userId") String userId);

    /** 取得特定公告的已讀人數 */
    long countByAnnouncementId(Long announcementId);

    /**
     * 批次取得所有公告的已讀人數（解決 getAllForAdmin N+1 問題）
     *
     * 面試重點：用 GROUP BY 一次查回所有公告的 readCount，
     *           取代逐一 countByAnnouncementId() 的 N+1 查詢。
     *
     * 回傳 Object[]：[0] announcementId(Long), [1] readCount(Long)
     */
    @Query("SELECT art.announcementId, COUNT(art) FROM AnnouncementReadTracking art GROUP BY art.announcementId")
    List<Object[]> countReadPerAnnouncement();
}
