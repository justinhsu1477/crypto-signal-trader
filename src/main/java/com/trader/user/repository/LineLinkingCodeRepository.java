package com.trader.user.repository;

import com.trader.user.entity.LineLinkingCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface LineLinkingCodeRepository extends JpaRepository<LineLinkingCode, String> {

    Optional<LineLinkingCode> findByCodeAndUsedFalse(String code);

    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
