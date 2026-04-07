package com.trader.trading.repository;

import com.trader.trading.entity.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    Optional<PromptVersion> findByActiveTrue();

    List<PromptVersion> findAllByOrderByVersionDesc();

    Optional<PromptVersion> findByVersion(Integer version);

    @Query("SELECT COALESCE(MAX(p.version), 0) FROM PromptVersion p")
    int findMaxVersion();

    @Modifying
    @Query("UPDATE PromptVersion p SET p.active = false WHERE p.active = true")
    void deactivateAll();
}
