package com.trader.shared.repository;

import com.trader.shared.entity.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    Page<AdminAuditLog> findByAdminUserIdOrderByCreatedAtDesc(String adminUserId, Pageable pageable);

    Page<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, String targetId, Pageable pageable);

    Page<AdminAuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
}
