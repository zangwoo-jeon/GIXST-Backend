package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.OverseasStockStaticAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OverseasStockStaticAnalysisRepository extends JpaRepository<OverseasStockStaticAnalysis, Long> {
    Optional<OverseasStockStaticAnalysis> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);
}
