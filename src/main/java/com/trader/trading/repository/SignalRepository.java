package com.trader.trading.repository;

import com.trader.trading.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<Signal, String> {

    List<Signal> findBySymbol(String symbol);

    List<Signal> findBySignalHash(String signalHash);

    List<Signal> findByExecutionStatus(String executionStatus);
}
