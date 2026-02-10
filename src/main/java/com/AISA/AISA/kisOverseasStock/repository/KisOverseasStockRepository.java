package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KisOverseasStockRepository extends JpaRepository<Stock, Long> {

    @Query("SELECT s FROM Stock s WHERE (s.stockCode LIKE %:keyword% OR s.stockName LIKE %:keyword%) AND s.stockType IN ('US_STOCK', 'US_ETF')")
    List<Stock> findByKeyword(@Param("keyword") String keyword);

    Optional<Stock> findByStockCode(String stockCode);

    List<Stock> findAllByStockType(Stock.StockType stockType);

    List<Stock> findAllByStockTypeInAndStockIdGreaterThanEqual(List<Stock.StockType> stockTypes, Long stockId);

    Optional<Stock> findByStockCodeAndStockType(String stockCode, Stock.StockType stockType);

    List<Stock> findAllByStockTypeAndStockIdGreaterThanEqual(Stock.StockType stockType, Long stockId);

    @Query("SELECT s FROM Stock s WHERE s.stockType IN ('US_STOCK', 'US_ETF') AND NOT EXISTS (SELECT 1 FROM OverseasStockDailyData d WHERE d.stock = s)")
    List<Stock> findStocksWithNoDailyData();

    @Modifying
    @Query(value = "UPDATE stock SET stock_code = :newCode WHERE stock_id = :stockId", nativeQuery = true)
    void updateStockCode(@Param("stockId") Long stockId, @Param("newCode") String newCode);
}
