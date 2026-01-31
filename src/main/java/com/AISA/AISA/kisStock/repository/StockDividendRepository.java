package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockDividendRepository extends JpaRepository<StockDividend, Long> {
        List<StockDividend> findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(String stockCode,
                        String startDate, String endDate);

        boolean existsByStock_StockCodeAndRecordDate(String stockCode, String recordDate);

        List<StockDividend> findByRecordDateBetweenOrderByRecordDateAsc(String startDate, String endDate);

        List<StockDividend> findByStock_StockCodeInAndRecordDateBetweenOrderByRecordDateAsc(List<String> stockCodes,
                        String startDate, String endDate);

        @Query("SELECT d FROM StockDividend d WHERE d.stock.stockType = com.AISA.AISA.kisStock.Entity.stock.Stock.StockType.US_STOCK AND (d.stockPrice IS NULL OR d.stockPrice = 0)")
        List<StockDividend> findUSDividendsWithMissingPrice();

        @Query("SELECT d FROM StockDividend d WHERE d.stock.stockCode IN :stockCodes AND "
                        +
                        "((d.recordDate BETWEEN :recordStart AND :recordEnd) OR " +
                        "(d.paymentDate BETWEEN :paymentStart AND :paymentEnd)) " +
                        "ORDER BY d.recordDate ASC")
        List<StockDividend> findDividendsByStockCodesAndDateRange(
                        @Param("stockCodes") List<String> stockCodes,
                        @Param("recordStart") String recordStart,
                        @Param("recordEnd") String recordEnd,
                        @Param("paymentStart") String paymentStart,
                        @Param("paymentEnd") String paymentEnd);
}
