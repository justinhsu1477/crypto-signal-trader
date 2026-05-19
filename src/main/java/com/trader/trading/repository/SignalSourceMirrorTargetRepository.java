package com.trader.trading.repository;

import com.trader.trading.entity.SignalSourceMirrorTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SignalSourceMirrorTargetRepository extends JpaRepository<SignalSourceMirrorTarget, Long> {

    List<SignalSourceMirrorTarget> findBySourceIdAndEnabledTrue(Long sourceId);

    Optional<SignalSourceMirrorTarget> findBySourceIdAndTargetChannelId(Long sourceId, String targetChannelId);
}
