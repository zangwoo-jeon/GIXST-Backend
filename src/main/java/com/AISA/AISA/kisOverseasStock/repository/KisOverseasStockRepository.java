package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KisOverseasStockRepository extends JpaRepository<Stock, Long> {

    @Query("SELECT s FROM Stock s WHERE (s.stockCode LIKE %:keyword% OR s.stockName LIKE %:keyword%) AND s.stockType = 'US_STOCK'")
    List<Stock> findByKeyword(@Param("keyword") String keyword);

    List<Stock> findAllByStockType(Stock.StockType stockType);

    List<Stock> findAllByStockTypeAndStockIdGreaterThanEqual(Stock.StockType stockType, Long stockId);
}
