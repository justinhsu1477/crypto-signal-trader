package com.trader.shared.repository;

import com.trader.shared.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 審計日誌 JPA Repository
 *
 * 提供審計日誌的 CRUD 和查詢操作
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 按用戶 ID 查找審計日誌（分頁）
     */
    Page<AuditLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    /**
     * 按操作類型查找審計日誌
     */
    List<AuditLog> findByActionOrderByTimestampDesc(String action);

    /**
     * 按操作狀態查找審計日誌
     */
    List<AuditLog> findByStatusOrderByTimestampDesc(String status);

    /**
     * 查找指定時間範圍內的審計日誌
     */
    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(
            LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 查找特定用戶在時間範圍內的失敗登入嘗試（用於檢測暴力攻擊）
     */
    List<AuditLog> findByUserIdAndActionAndStatusAndTimestampBetween(
            String userId, String action, String status,
            LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 查找特定 IP 的失敗登入嘗試（防暴力破解）
     */
    List<AuditLog> findByIpAddressAndActionAndStatusAndTimestampBetween(
            String ipAddress, String action, String status,
            LocalDateTime startTime, LocalDateTime endTime);
}
