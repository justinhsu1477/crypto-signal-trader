package com.trader.trading.repository;

import com.trader.trading.entity.UserSignalSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSignalSourceRepository extends JpaRepository<UserSignalSource, Long> {

    List<UserSignalSource> findByUserId(String userId);

    List<UserSignalSource> findByUserIdAndEnabledTrue(String userId);

    List<UserSignalSource> findBySourceId(Long sourceId);

    List<UserSignalSource> findBySourceIdAndEnabledTrue(Long sourceId);

    Optional<UserSignalSource> findByUserIdAndSourceId(String userId, Long sourceId);

    boolean existsByUserIdAndSourceId(String userId, Long sourceId);

    /** 檢查用戶是否已綁定任何來源（MVP 一對一限制用） */
    boolean existsByUserId(String userId);

    /** 取得某來源所有啟用綁定的 userId（廣播路由 hot path） */
    @Query("SELECT uss.userId FROM UserSignalSource uss WHERE uss.sourceId = :sourceId AND uss.enabled = true")
    List<String> findEnabledUserIdsBySourceId(@Param("sourceId") Long sourceId);

    /** 取得用戶所有啟用綁定的 sourceId */
    @Query("SELECT uss.sourceId FROM UserSignalSource uss WHERE uss.userId = :userId AND uss.enabled = true")
    List<Long> findEnabledSourceIdsByUserId(@Param("userId") String userId);

    void deleteByUserIdAndSourceId(String userId, Long sourceId);

    /** 取得所有綁定到啟用 ASSIGNED 來源的 userId（GLOBAL 路由排除用） */
    @Query(value = "SELECT DISTINCT uss.user_id FROM user_signal_sources uss " +
            "JOIN signal_sources ss ON uss.source_id = ss.id " +
            "WHERE ss.routing_mode = 'ASSIGNED' AND uss.enabled = true AND ss.enabled = true",
            nativeQuery = true)
    List<String> findUserIdsBoundToEnabledAssignedSources();
}
