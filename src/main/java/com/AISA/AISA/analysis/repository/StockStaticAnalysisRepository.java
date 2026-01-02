package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.StockStaticAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockStaticAnalysisRepository extends JpaRepository<StockStaticAnalysis, Long> {
    Optional<StockStaticAnalysis> findByStockCode(String stockCode);
}
