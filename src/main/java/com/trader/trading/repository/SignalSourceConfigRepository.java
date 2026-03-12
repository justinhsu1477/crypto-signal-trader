package com.trader.trading.repository;

import com.trader.trading.entity.SignalSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SignalSourceConfigRepository extends JpaRepository<SignalSourceConfig, Long> {

    Optional<SignalSourceConfig> findByChannelIdAndGuildId(String channelId, String guildId);

    Optional<SignalSourceConfig> findByChannelId(String channelId);

    List<SignalSourceConfig> findByEnabledTrue();

    List<SignalSourceConfig> findAllByOrderByCreatedAtDesc();

    boolean existsByChannelIdAndGuildId(String channelId, String guildId);

    boolean existsByRoutingMode(SignalSourceConfig.RoutingMode routingMode);

    boolean existsByRoutingModeAndIdNot(SignalSourceConfig.RoutingMode routingMode, Long id);
}
