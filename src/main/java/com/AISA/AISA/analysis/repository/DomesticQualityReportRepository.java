package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.DomesticQualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DomesticQualityReportRepository extends JpaRepository<DomesticQualityReport, Long> {
    Optional<DomesticQualityReport> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);

    @Modifying
    @Query(value = "TRUNCATE TABLE domestic_quality_report", nativeQuery = true)
    void truncateTable();
}
