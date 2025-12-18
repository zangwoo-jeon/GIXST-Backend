package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.Index.OverseasIndexDailyData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OverseasIndexDailyDataRepository extends JpaRepository<OverseasIndexDailyData, Long> {

    // 특정 종목, 특정 날짜 데이터 조회
    Optional<OverseasIndexDailyData> findByMarketNameAndDate(String marketName, LocalDate date);

    // 특정 종목, 날짜 범위 조회 (오름차순)
    List<OverseasIndexDailyData> findAllByMarketNameAndDateBetweenOrderByDateAsc(String marketName, LocalDate startDate,
            LocalDate endDate);
}
