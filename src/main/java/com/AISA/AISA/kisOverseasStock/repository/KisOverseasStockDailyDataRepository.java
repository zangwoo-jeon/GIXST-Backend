package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KisOverseasStockDailyDataRepository extends JpaRepository<OverseasStockDailyData, Long> {

    @Query("SELECT d FROM OverseasStockDailyData d WHERE d.stock = :stock AND d.date BETWEEN :startDate AND :endDate ORDER BY d.date ASC")
    List<OverseasStockDailyData> findByStockAndDateBetween(@Param("stock") Stock stock,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT MAX(d.date) FROM OverseasStockDailyData d WHERE d.stock = :stock")
    Optional<LocalDate> findLatestDateByStock(@Param("stock") Stock stock);

    boolean existsByStockAndDate(Stock stock, LocalDate date);
}
