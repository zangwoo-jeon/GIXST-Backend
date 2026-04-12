package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockBalanceSheetRepository extends JpaRepository<StockBalanceSheet, Long> {
    List<StockBalanceSheet> findByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

    StockBalanceSheet findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

    List<StockBalanceSheet> findByDivCodeAndStacYymmGreaterThanEqual(String divCode, String stacYymm);

    List<StockBalanceSheet> findAllByDivCode(String divCode);

    List<StockBalanceSheet> findByStockCodeInAndDivCode(java.util.Collection<String> stockCodes, String divCode);

    Optional<StockBalanceSheet> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);

    // divCode=0(연간)에서 12월 결산 데이터가 있는 연도의 비결산 데이터 삭제
    @Modifying
    @Query("DELETE FROM StockBalanceSheet s WHERE s.stockCode = :stockCode AND s.divCode = :divCode AND SUBSTRING(s.stacYymm, 1, 4) = :year AND s.stacYymm <> :decYymm")
    void deleteNonDecemberAnnualDataForYear(@Param("stockCode") String stockCode, @Param("divCode") String divCode,
            @Param("year") String year, @Param("decYymm") String decYymm);
}
