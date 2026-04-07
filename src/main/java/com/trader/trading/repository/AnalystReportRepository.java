package com.trader.trading.repository;

import com.trader.trading.entity.AnalystReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AnalystReportRepository extends JpaRepository<AnalystReport, Long> {

    Optional<AnalystReport> findByReportDate(LocalDate reportDate);

    Page<AnalystReport> findAllByOrderByReportDateDesc(Pageable pageable);
}
