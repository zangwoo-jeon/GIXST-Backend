package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// import com.AISA.AISA.kisStock.enums.Industry; // Removed
// import com.AISA.AISA.kisStock.enums.SubIndustry; // Removed
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockRepository extends JpaRepository<Stock, Long> {
        Optional<Stock> findByStockCode(String stockCode);

        List<Stock> findByStockType(Stock.StockType stockType);

        List<Stock> findAllByStockIdBetween(Long startId, Long endId);

        List<Stock> findByStockCodeContainingOrStockNameContaining(String stockCode, String stockName);

        // Domestic Search
        @Query("SELECT s FROM Stock s WHERE (s.stockCode LIKE %:keyword% OR s.stockName LIKE %:keyword%) AND s.stockType = 'DOMESTIC'")
        List<Stock> findDomesticByKeyword(@Param("keyword") String keyword);

        // List View API: Find Stocks by SubIndustry Code with Pagination
        @Query("SELECT s FROM Stock s JOIN s.stockIndustries si JOIN si.subIndustry sub WHERE sub.code = :subIndustryCode")
        Page<Stock> findBySubIndustryCode(@Param("subIndustryCode") String subIndustryCode, Pageable pageable);

        // Competitor Finding: Find by SubIndustry Code via Join Table
        @Query("SELECT s FROM Stock s JOIN s.stockIndustries si JOIN si.subIndustry sub WHERE sub.code = :subIndustryCode AND s.stockCode <> :stockCode")
        List<Stock> findBySubIndustryCodeAndStockCodeNot(
                        @Param("subIndustryCode") String subIndustryCode,
                        @Param("stockCode") String stockCode);

        // Fallback: Find by Industry Code
        @Query("SELECT s FROM Stock s JOIN s.stockIndustries si JOIN si.subIndustry sub JOIN sub.industry ind WHERE ind.code = :industryCode AND s.stockCode <> :stockCode")
        List<Stock> findByIndustryCodeAndStockCodeNot(
                        @Param("industryCode") String industryCode,
                        @Param("stockCode") String stockCode);
}
