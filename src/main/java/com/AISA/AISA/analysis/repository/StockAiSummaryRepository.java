package com.AISA.AISA.analysis.repository;

import com.AISA.AISA.analysis.entity.StockAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockAiSummaryRepository extends JpaRepository<StockAiSummary, Long> {
    Optional<StockAiSummary> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);
}
