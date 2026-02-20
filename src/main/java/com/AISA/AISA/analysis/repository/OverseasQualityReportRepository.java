package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.OverseasQualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OverseasQualityReportRepository extends JpaRepository<OverseasQualityReport, Long> {
    Optional<OverseasQualityReport> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);

    @Modifying
    @Query(value = "TRUNCATE TABLE overseas_quality_report", nativeQuery = true)
    void truncateTable();
}
