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
}
