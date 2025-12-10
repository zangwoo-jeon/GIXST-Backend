package com.AISA.AISA.portfolio.macro.repository;

import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MacroDailyDataRepository extends JpaRepository<MacroDailyData, Long> {

    // Find data for a specific range
    List<MacroDailyData> findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
            String statCode, String itemCode, LocalDate startDate, LocalDate endDate);

    // Find the latest data entry to determine the gap
    Optional<MacroDailyData> findTopByStatCodeAndItemCodeOrderByDateDesc(String statCode, String itemCode);
}
