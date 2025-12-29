package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import org.springframework.data.jpa.repository.JpaRepository;
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
        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderBySaleAccountDesc(
                        String stacYymm,
                        String divCode);

        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderByOperatingProfitDesc(
                        String stacYymm,
                        String divCode);

        List<StockFinancialStatement> findTop20ByStacYymmAndDivCodeAndIsSuspendedFalseOrderByNetIncomeDesc(
                        String stacYymm,
                        String divCode);

        // 영업이익률(operatingProfit / saleAccount) 상위 20개 조회 (매출액 0 제외, 거래정지 제외)
        @Query("SELECT s FROM StockFinancialStatement s " +
                        "WHERE s.stacYymm = :stacYymm AND s.divCode = :divCode AND s.saleAccount > 0 AND s.isSuspended = false "
                        +
                        "ORDER BY (s.operatingProfit / s.saleAccount) DESC")
        List<StockFinancialStatement> findTop20ByOperatingMarginDesc(@Param("stacYymm") String stacYymm,
                        @Param("divCode") String divCode, org.springframework.data.domain.Pageable pageable);
}
