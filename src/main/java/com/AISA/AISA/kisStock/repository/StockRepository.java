package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import com.AISA.AISA.kisStock.enums.Industry;
import com.AISA.AISA.kisStock.enums.SubIndustry;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByStockCode(String stockCode);

    List<Stock> findAllByStockIdBetween(Long startId, Long endId);

    List<Stock> findByStockCodeContainingOrStockNameContaining(String stockCode, String stockName);

    // Industry Categorization
    List<Stock> findByIndustry(Industry industry);

    List<Stock> findBySubIndustry(SubIndustry subIndustry);

    // Competitor Finding: Find by SubIndustry except self
    List<Stock> findBySubIndustryAndStockCodeNot(SubIndustry subIndustry, String stockCode);

    // Fallback: Find by Industry except self
    List<Stock> findByIndustryAndStockCodeNot(Industry industry, String stockCode);
}
