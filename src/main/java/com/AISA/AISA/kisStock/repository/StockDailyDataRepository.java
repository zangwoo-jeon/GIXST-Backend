package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockDailyDataRepository extends JpaRepository<StockDailyData, Long> {
        Optional<StockDailyData> findByStockAndDate(Stock stock, LocalDate date);

        List<StockDailyData> findAllByStock_StockCodeAndDateBetweenOrderByDateDesc(
                        String stockCode, LocalDate startDate, LocalDate endDate);

        List<StockDailyData> findAllByStockInAndDateBetweenOrderByDateAsc(
                        List<Stock> stocks, LocalDate startDate, LocalDate endDate);

        Optional<StockDailyData> findFirstByStockAndDateLessThanEqualOrderByDateDesc(Stock stock, LocalDate date);

        Optional<StockDailyData> findFirstByStockAndDateGreaterThanEqualOrderByDateAsc(Stock stock, LocalDate date);

        List<StockDailyData> findByStock_StockCodeAndDateBetweenOrderByDateAsc(String stockCode, LocalDate startDate,
                        LocalDate endDate);
}
