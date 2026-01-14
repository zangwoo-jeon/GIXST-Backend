package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface StockFinancialStatementRepository extends JpaRepository<StockFinancialStatement, Long> {
        Optional<StockFinancialStatement> findByStockCodeAndStacYymm(String stockCode, String stacYymm);

        Optional<StockFinancialStatement> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
                        String divCode);

        List<StockFinancialStatement> findByStockCodeOrderByStacYymmDesc(String stockCode);

        List<StockFinancialStatement> findByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

        List<StockFinancialStatement> findByStockCodeAndDivCodeOrderByStacYymmAsc(String stockCode, String divCode);

        List<StockFinancialStatement> findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode,
                        String divCode);

        StockFinancialStatement findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

        void deleteByStockCode(String stockCode);

        // Initial Ranking Queries (Limit will be handled by Pageable or Top keyword if
        // supported, but standard JPA supports Top)
        List<StockFinancialStatement> findTop100ByStacYymmOrderBySaleAccountDesc(String stacYymm);

        List<StockFinancialStatement> findTop100ByStacYymmOrderByOperatingProfitDesc(String stacYymm);

        List<StockFinancialStatement> findTop100ByStacYymmOrderByNetIncomeDesc(String stacYymm);

        // Find latest Year-Month
        Optional<StockFinancialStatement> findTop1ByOrderByStacYymmDesc();

        // Find latest Year-Month by DivCode
        Optional<StockFinancialStatement> findTop1ByDivCodeOrderByStacYymmDesc(String divCode);

        // Top 20 Ranking Queries
        @Query("SELECT s FROM StockFinancialStatement s WHERE s.stacYymm = :stacYymm AND s.divCode = :divCode AND s.isSuspended = false ORDER BY s.saleAccount DESC")
        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderBySaleAccountDesc(
                        @Param("stacYymm") String stacYymm,
                        @Param("divCode") String divCode,
                        Pageable pageable);

        @Query("SELECT s FROM StockFinancialStatement s WHERE s.stacYymm = :stacYymm AND s.divCode = :divCode AND s.isSuspended = false ORDER BY s.operatingProfit DESC")
        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderByOperatingProfitDesc(
                        @Param("stacYymm") String stacYymm,
                        @Param("divCode") String divCode,
                        Pageable pageable);

        @Query("SELECT s FROM StockFinancialStatement s WHERE s.stacYymm = :stacYymm AND s.divCode = :divCode AND s.isSuspended = false ORDER BY s.netIncome DESC")
        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderByNetIncomeDesc(
                        @Param("stacYymm") String stacYymm,
                        @Param("divCode") String divCode,
                        Pageable pageable);

        // 영업이익률(operatingProfit / saleAccount) 상위 20개 조회 (매출액 0 제외, 거래정지 제외)
        @Query("SELECT s FROM StockFinancialStatement s " +
                        "WHERE s.stacYymm = :stacYymm AND s.divCode = :divCode AND s.saleAccount > 0 AND s.isSuspended = false "
                        +
                        "ORDER BY (s.operatingProfit / s.saleAccount) DESC")
        List<StockFinancialStatement> findTop20ByOperatingMarginDesc(@Param("stacYymm") String stacYymm,
                        @Param("divCode") String divCode, Pageable pageable);

        // Fetch ALL statements for a given divCode to perform in-memory grouping and
        // TTM calculation
        List<StockFinancialStatement> findAllByDivCode(String divCode);

        List<StockFinancialStatement> findByDivCodeAndStacYymmGreaterThanEqual(String divCode, String stacYymm);
}
