package com.trader.trading.repository;

import com.trader.trading.entity.DailySignalReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailySignalReportRepository extends JpaRepository<DailySignalReport, Long> {

    Optional<DailySignalReport> findByReportDate(LocalDate reportDate);

    Page<DailySignalReport> findAllByOrderByReportDateDesc(Pageable pageable);
}
