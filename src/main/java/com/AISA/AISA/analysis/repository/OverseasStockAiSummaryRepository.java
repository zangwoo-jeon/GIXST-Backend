package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.OverseasStockAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OverseasStockAiSummaryRepository extends JpaRepository<OverseasStockAiSummary, Long> {
    Optional<OverseasStockAiSummary> findByStockCode(String stockCode);
}
