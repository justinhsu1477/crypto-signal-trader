package com.trader.trading.repository;

import com.trader.trading.entity.TradeNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeNoteRepository extends JpaRepository<TradeNote, Long> {

    Optional<TradeNote> findByTradeIdAndUserId(String tradeId, String userId);

    List<TradeNote> findByUserIdOrderByUpdatedAtDesc(String userId);
}
