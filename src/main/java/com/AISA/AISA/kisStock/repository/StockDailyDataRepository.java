package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.enums.MarketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.web.bind.annotation.RequestParam;

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

        long countByDateAndStock_MarketNameAndChangeRateGreaterThan(LocalDate date,
                        MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketNameAndChangeRateLessThan(LocalDate date,
                        MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketName(LocalDate date, MarketType marketName);

        // New methods with isSuspended filter
        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndStock_IsSuspendedFalseAndChangeRateGreaterThan(
                        LocalDate date, MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndStock_IsSuspendedFalseAndChangeRateLessThan(
                        LocalDate date, MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndStock_IsSuspendedFalseAndChangeRate(
                        LocalDate date, MarketType marketName, Double changeRate);

        // New methods without isSuspended filter (for more accurate historical
        // counting)
        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRateGreaterThan(
                        LocalDate date, MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRateLessThan(
                        LocalDate date, MarketType marketName, Double changeRate);

        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRate(
                        LocalDate date, MarketType marketName, Double changeRate);

        StockDailyData findTop1ByStockAndDateLessThanOrderByDateDesc(Stock stock, LocalDate date);

        long countByDateAndStock_MarketNameAndStock_IsCommonTrueAndStock_IsSuspendedFalse(
                        LocalDate date, MarketType marketName);

        @Query("SELECT MAX(sdd.date) FROM StockDailyData sdd JOIN sdd.stock s WHERE s.marketName = :marketName")
        Optional<LocalDate> findMaxDateByMarketName(
                        @RequestParam("marketName") MarketType marketName);
}
