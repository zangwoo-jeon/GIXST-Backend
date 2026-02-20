package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.OverseasShortTermReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OverseasShortTermReportRepository extends JpaRepository<OverseasShortTermReport, Long> {
    Optional<OverseasShortTermReport> findByStockCode(String stockCode);

    @Modifying
    @Query(value = "TRUNCATE TABLE overseas_short_term_report", nativeQuery = true)
    void truncateTable();
}
