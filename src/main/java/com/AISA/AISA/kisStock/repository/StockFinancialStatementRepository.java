package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

        // divCode=0(연간)에서 12월 결산 데이터가 있는 연도의 비결산 데이터 삭제
        @Modifying
        @Query("DELETE FROM StockFinancialStatement s WHERE s.stockCode = :stockCode AND s.divCode = :divCode AND SUBSTRING(s.stacYymm, 1, 4) = :year AND s.stacYymm <> :decYymm")
        void deleteNonDecemberAnnualDataForYear(@Param("stockCode") String stockCode, @Param("divCode") String divCode,
                        @Param("year") String year, @Param("decYymm") String decYymm);

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

        List<StockFinancialStatement> findByStockCodeInAndDivCode(java.util.Collection<String> stockCodes,
                        String divCode);
}
