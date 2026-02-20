package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.DomesticShortTermReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DomesticShortTermReportRepository extends JpaRepository<DomesticShortTermReport, Long> {

    Optional<DomesticShortTermReport> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);

    @Modifying
    @Query(value = "TRUNCATE TABLE domestic_short_term_report", nativeQuery = true)
    void truncateTable();
}
